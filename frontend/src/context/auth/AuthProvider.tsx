import { Loading } from "@carbon/react";
import axios from "axios";
import type { User } from "oidc-client-ts";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { bootstrapLogin, fetchSelf } from "@/services/AuthApiService";
import {
    AUTH_CALLBACK_PATH,
    getUserManager,
    KC_IDP_HINT,
    loadStoredUser,
} from "@/services/keycloak";
import { ROUTES } from "@/routes/routePaths";
import type { AuthState, FamLoginUser, IdpTypes } from "@/types/AuthTypes";
import { IdpProvider } from "@/enum/IdpEnum";
import { AuthContext } from "./AuthContext";

const ONE_SECOND = 1000;
const THREE_MINUTES = 3 * 60 * ONE_SECOND;
const HALF_HOUR = 30 * 60 * ONE_SECOND;

const REFRESH_INTERVAL = THREE_MINUTES;
const INACTIVITY_TIMEOUT = HALF_HOUR;

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
    const inactivityTimeoutId = useRef<number | null>(null);
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
     */
    const logout = useCallback(async () => {
        stopSilentRefresh();
        delete axios.defaults.headers.common["Authorization"];
        setAuthState({ ...SIGNED_OUT, isAuthRestored: true });
        await getUserManager().signoutRedirect();
    }, [stopSilentRefresh]);

    const resetInactivityTimeout = useCallback(() => {
        if (inactivityTimeoutId.current) {
            clearTimeout(inactivityTimeoutId.current);
        }
        inactivityTimeoutId.current = window.setTimeout(() => {
            // Read from the setter rather than a captured value: this closure
            // outlives the render that made it, and a stale `isAuthenticated`
            // would either log out a signed-out user or never log out at all.
            setAuthState((current) => {
                if (current.isAuthenticated) {
                    void logout();
                }
                return current;
            });
        }, INACTIVITY_TIMEOUT);
    }, [logout]);

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
        }, REFRESH_INTERVAL);
    }, [loadUser, logout, stopSilentRefresh]);

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

        const onActivity = debounce(resetInactivityTimeout, ONE_SECOND);
        window.addEventListener("mousemove", onActivity);
        window.addEventListener("keydown", onActivity);
        window.addEventListener("click", onActivity);

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
                    resetInactivityTimeout();
                    navigate(ROUTES.managePermissions, { replace: true });
                    return;
                }

                await loadUser();
                startSilentRefresh();
                resetInactivityTimeout();
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
            if (inactivityTimeoutId.current) {
                clearTimeout(inactivityTimeoutId.current);
            }
            window.removeEventListener("mousemove", onActivity);
            window.removeEventListener("keydown", onActivity);
            window.removeEventListener("click", onActivity);
        };
        // Intentionally once: the guard above makes re-runs no-ops, and the
        // callbacks are stable.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    if (isLoading) {
        return <Loading description="Signing in" withOverlay />;
    }

    return (
        <AuthContext.Provider value={{ authState, login, logout }}>
            {children}
        </AuthContext.Provider>
    );
};

/** So a mouse move does not reset the inactivity timer on every pixel. */
function debounce<T extends (...args: never[]) => void>(fn: T, delay: number) {
    let timer: ReturnType<typeof setTimeout> | null = null;
    return (...args: Parameters<T>) => {
        if (timer) {
            clearTimeout(timer);
        }
        timer = setTimeout(() => fn(...args), delay);
    };
}
