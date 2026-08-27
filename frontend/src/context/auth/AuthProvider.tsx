import { Loading } from "@carbon/react";
import axios from "axios";
import type { User } from "oidc-client-ts";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { bootstrapLogin, fetchSelf } from "@/services/AuthApiService";
import {
    AUTH_CALLBACK_PATH,
    ensureFreshToken as ensureFreshTokenFor,
    forceRenew,
    getUserManager,
    KC_IDP_HINT,
    loadStoredUser,
} from "@/services/keycloak";
import { ROUTES } from "@/routes/routePaths";
import type { AuthState, FamLoginUser, IdpTypes } from "@/types/AuthTypes";
import { IdpProvider } from "@/enum/IdpEnum";
import { AuthContext } from "./AuthContext";
import { markSessionExpired } from "./sessionExpiry";

const ONE_SECOND = 1000;

/**
 * How often the background renewal wakes up - not how often it renews.
 *
 * It renews only when the token is inside the skew window (see
 * RENEW_WHEN_SECONDS_LEFT), so this is a polling rate rather than a token
 * lifetime. It has to divide the access token's five minutes finely enough that
 * the check lands inside that window: this used to poll every three minutes and
 * only renew once the token had <em>already</em> expired, which left up to three
 * minutes in every five where the app held a token the backend refused.
 *
 * The idle guard - see components/SessionTimeout - is what ends a session. This
 * only keeps a live one usable while background queries are running.
 */
const REFRESH_POLL_INTERVAL = 60 * ONE_SECOND;

const SIGNED_OUT: AuthState = {
    isAuthenticated: false,
    famLoginUser: null,
    isAuthRestored: false,
    accessRoles: [],
};

/**
 * Display information from the token's profile claims.
 *
 * Claim names come from BC Gov SSO's identity mappers:
 *   - IDIR:           `idir_username`
 *   - Business BCeID: `bceid_username`, `bceid_business_name`
 */
const getFamLoginUser = (user: User): FamLoginUser => {
    const profile = user.profile as Record<string, unknown>;
    return {
        username:
            (profile["idir_username"] as string) ??
            (profile["bceid_username"] as string) ??
            undefined,
        displayName:
            (profile["display_name"] as string) ?? (profile["name"] as string),
        email: profile["email"] as string,
        idpProvider: (profile["identity_provider"] as string)?.toLowerCase(),
        organization: profile["bceid_business_name"] as string,
    };
};

export const AuthProvider = ({ children }: { children: ReactNode }) => {
    const [authState, setAuthState] = useState<AuthState>(SIGNED_OUT);
    const [isLoading, setLoading] = useState(true);
    const navigate = useNavigate();

    // Timers and re-entrancy flags live in refs: changing them must not
    // re-render, and a re-render must not restart them.
    const refreshIntervalId = useRef<number | null>(null);
    const isRefreshing = useRef(false);
    const bootstrapped = useRef(false);

    const stopSilentRefresh = useCallback(() => {
        if (refreshIntervalId.current) {
            clearInterval(refreshIntervalId.current);
        }
        refreshIntervalId.current = null;
    }, []);

    /**
     * Ends the Keycloak session.
     *
     * <b>The stored user is deliberately left in place.</b> oidc-client-ts reads
     * `id_token_hint` off it and removes it itself; clearing it here first sends
     * a logout Keycloak cannot attribute to a session, so the realm session
     * survives and the next sign-in walks straight back in.
     *
     * <b>And it always finishes.</b> This used to publish the signed-out state
     * first and then await the redirect with nothing catching it. Every caller
     * invokes it as `void logout()`, so a redirect that threw - a silent renewal
     * having already removed the stored user, Keycloak refusing the request -
     * rejected into nothing, leaving the app showing a signed-out page on top of
     * a realm session that was still very much alive. Pressing sign in walked
     * straight back in without a prompt, which is what "logout does not work"
     * looks like from the outside.
     */
    const logout = useCallback(
        async ({ expired = false }: { expired?: boolean } = {}) => {
            stopSilentRefresh();
            delete axios.defaults.headers.common["Authorization"];

            // Written before leaving: the sign-in screen is a fresh page load
            // after the round trip through Keycloak.
            if (expired) {
                markSessionExpired();
            }

            try {
                await getUserManager().signoutRedirect();
            } catch (error) {
                // The realm session may survive this - we cannot reach it - but
                // this browser's must not. Drop the tokens and land on the
                // sign-in screen under our own steam rather than stalling on a
                // page the person believes they have left.
                console.error(
                    "The sign-out redirect failed. Clearing the local session instead.",
                    error
                );
                await getUserManager()
                    .removeUser()
                    .catch(() => undefined);
                setAuthState({ ...SIGNED_OUT, isAuthRestored: true });
                window.location.assign(ROUTES.landing);
            }
        },
        [stopSilentRefresh]
    );

    /** Publishes the session. Not `isAuthRestored` - see loadUser. */
    const applySession = useCallback((user: User) => {
        axios.defaults.headers.common["Authorization"] =
            `Bearer ${user.access_token}`;
        setAuthState((current) => ({
            ...current,
            isAuthenticated: true,
            famLoginUser: getFamLoginUser(user),
        }));
    }, []);

    /**
     * Restores a stored session, or throws when nobody is signed in.
     *
     * Roles are re-read on every refresh so a permission change takes effect
     * without a fresh sign-in - they live in CSS, not in the token.
     */
    const loadUser = useCallback(async () => {
        const user = await loadStoredUser(getUserManager());
        if (!user?.access_token) {
            throw new Error("The user is not authenticated");
        }
        applySession(user);

        const accessRoles = (await fetchSelf()).access_roles ?? [];

        // Last, and only now. The route guards treat this as "the session is
        // ready to be judged", and judging it needs the roles: setting it in
        // applySession left a window where a FAM administrator was judged with
        // an empty role list and sent to /no-access.
        setAuthState((current) => ({
            ...current,
            accessRoles,
            isAuthRestored: true,
        }));
    }, [applySession]);

    const startSilentRefresh = useCallback(() => {
        stopSilentRefresh();
        refreshIntervalId.current = window.setInterval(async () => {
            if (isRefreshing.current) {
                return;
            }
            try {
                isRefreshing.current = true;
                await loadUser();
            } catch (error) {
                console.error("Silent refresh failed:", error);
                void logout();
            } finally {
                isRefreshing.current = false;
            }
        }, REFRESH_POLL_INTERVAL);
    }, [loadUser, logout, stopSilentRefresh]);

    /**
     * Renews the access token if it is at or near expiry, otherwise does
     * nothing.
     *
     * Driven by user activity rather than by a clock, which is the case a timer
     * cannot cover: somebody reading a long table makes no request at all, so
     * nothing else would keep their token alive while they are plainly still
     * there.
     */
    const ensureFreshToken = useCallback(async () => {
        await ensureFreshTokenFor(getUserManager());
    }, []);

    /**
     * Renews now, whatever the access token has left.
     *
     * What "Stay logged in" needs. The access token is not really the point -
     * using the refresh token rotates it, and that is what moves the
     * thirty-minute ceiling and buys the time the button offers. Throws if the
     * refresh token has gone, which the caller must treat as a real expiry.
     */
    const forceRefreshSession = useCallback(async () => {
        const user = await forceRenew(getUserManager());
        if (!user?.access_token) {
            throw new Error("The session could not be renewed");
        }
        axios.defaults.headers.common["Authorization"] =
            `Bearer ${user.access_token}`;
    }, []);

    const login = useCallback(async (idp: IdpTypes) => {
        try {
            await getUserManager().signinRedirect({
                extraQueryParams: {
                    kc_idp_hint:
                        idp === IdpProvider.IDIR
                            ? KC_IDP_HINT.IDIR
                            : KC_IDP_HINT.BCEIDBUSINESS,
                },
            });
        } catch (error) {
            console.error("Login failed:", error);
        }
    }, []);

    useEffect(() => {
        // React 19 StrictMode mounts effects twice in development. The
        // authorization code is single-use, so a second exchange fails and
        // would log the user straight back out - this runs once either way.
        if (bootstrapped.current) {
            return;
        }
        bootstrapped.current = true;

        const start = async () => {
            try {
                if (window.location.pathname === AUTH_CALLBACK_PATH) {
                    const user =
                        await getUserManager().signinRedirectCallback();
                    applySession(user);
                    const accessRoles =
                        (await bootstrapLogin()).access_roles ?? [];
                    setAuthState((current) => ({
                        ...current,
                        accessRoles,
                        isAuthRestored: true,
                    }));
                    startSilentRefresh();
                    navigate(ROUTES.managePermissions, { replace: true });
                    return;
                }

                await loadUser();
                startSilentRefresh();
            } catch (error) {
                // Distinguishes "nobody is signed in" from "the session was
                // refused". The Vue version logged both as one warning and
                // de-authenticated, so a backend rejecting every token looked
                // identical to a first visit - which cost an afternoon.
                const status = (error as { response?: { status?: number } })
                    ?.response?.status;
                if (status === 401 || status === 403) {
                    console.error(
                        `The API refused this session (HTTP ${status}). The token is ` +
                            "being rejected - check that the SPA and the backend " +
                            "agree on the realm and client.",
                        error
                    );
                } else {
                    console.info("No session to restore.", error);
                }
                setAuthState({ ...SIGNED_OUT, isAuthRestored: true });
            } finally {
                setLoading(false);
            }
        };

        void start();

        return () => {
            stopSilentRefresh();
        };
        // Intentionally once: the guard above makes re-runs no-ops, and the
        // callbacks are stable.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    if (isLoading) {
        return <Loading description="Signing in" withOverlay />;
    }

    return (
        <AuthContext.Provider
            value={{
                authState,
                login,
                logout,
                ensureFreshToken,
                forceRefreshSession,
            }}
        >
            {children}
        </AuthContext.Provider>
    );
};
