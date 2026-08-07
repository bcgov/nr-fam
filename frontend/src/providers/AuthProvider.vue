<script setup lang="ts">
import { ref, provide, onMounted, onBeforeUnmount, readonly } from "vue";
import axios from "axios";
import type { User } from "oidc-client-ts";
import type { IdpTypes, AuthContext, FamLoginUser } from "@/types/AuthTypes";
import { AUTH_KEY } from "@/constants/InjectionKeys";
import { ONE_SECOND, THREE_MINUTES, HALF_HOUR } from "@/constants/TimeUnits";
import { IdpProvider } from "@/enum/IdpEnum";
import { EnvironmentSettings } from "@/services/EnvironmentSettings";
import {
    getUserManager,
    KC_IDP_HINT,
    AUTH_CALLBACK_PATH,
} from "@/services/keycloak";
import { bootstrapLogin, fetchSelf } from "@/services/AuthApiService";
import { authState } from "@/providers/authState";
import { useRouter } from "vue-router";
import Spinner from "@/components/UI/Spinner.vue";

const environmentSettings = new EnvironmentSettings();
const REFRESH_INTERVAL = THREE_MINUTES;
const INACTIVITY_TIMEOUT = HALF_HOUR;

let refreshIntervalId: number | null = null;
let inactivityTimeoutId: number | null = null;
const isLoading = ref(false); // Loading state for animation
const router = useRouter();

const setAxiosAuthorizationHeader = (token: string) => {
    axios.defaults.headers.common["Authorization"] = `Bearer ${token}`;
};

/**
 * Resets the inactivity timeout and sets a new timeout to log the user out after a period of inactivity.
 * If the user is inactive (no mouse or keyboard input) for the defined `INACTIVITY_TIMEOUT`,
 * and is still authenticated, they will be logged out and the silent refresh will stop.
 */
const resetInactivityTimeout = () => {
    if (inactivityTimeoutId) clearTimeout(inactivityTimeoutId);

    inactivityTimeoutId = window.setTimeout(() => {
        if (authState.value.isAuthenticated) {
            console.log("User inactive, logging out.");
            logout();
        }
    }, INACTIVITY_TIMEOUT);
};

/**
 * Stops the silent token refresh process.
 */
const stopSilentRefresh = () => {
    if (refreshIntervalId) clearInterval(refreshIntervalId);
    refreshIntervalId = null;
};

/**
 * Starts a sign-in redirect to BC Gov SSO.
 *
 * `kc_idp_hint` sends the browser straight to the chosen provider rather than
 * Keycloak's provider-selection screen, preserving the two-button landing page.
 *
 * @param {IdpTypes} idP The identity provider (IDIR or Business BCeID).
 */
const login = async (idP: IdpTypes) => {
    try {
        await getUserManager().signinRedirect({
            extraQueryParams: {
                kc_idp_hint:
                    idP === IdpProvider.IDIR
                        ? KC_IDP_HINT.IDIR
                        : KC_IDP_HINT.BCEIDBUSINESS,
            },
        });
    } catch (error) {
        console.error("Login failed:", error);
    }
};

/**
 * Logs the user out and resets authentication state.
 *
 * Redirects through Keycloak's end-session endpoint, which ends the realm
 * session and returns to `post_logout_redirect_uri` - the app origin, set in
 * `keycloak.ts`. That origin must be on the client's post-logout redirect
 * allow-list in the realm, or Keycloak refuses the return leg.
 *
 * This ends the Keycloak session only. An upstream Siteminder session, if the
 * provider has one, outlives it - so a later sign-in through that provider may
 * not prompt for credentials again.
 *
 * Local state is cleared first, but the stored user is left for
 * `signoutRedirect` to remove: it needs the id_token_hint to tell Keycloak which
 * session to end.
 */
const logout = async () => {
    stopSilentRefresh();

    delete axios.defaults.headers.common["Authorization"];

    authState.value = {
        isAuthenticated: false,
        famLoginUser: null,
        isAuthRestored: true,
    };

    await getUserManager().signoutRedirect();
};

/**
 * Extracts display information from the token's profile claims.
 *
 * Claim names come from BC Gov SSO's identity mappers, replacing Cognito's
 * `custom:idp_*` attributes:
 *   - IDIR:           `idir_username`
 *   - Business BCeID: `bceid_username`, `bceid_business_name`
 */
const getFamLoginUser = (user: User): FamLoginUser => {
    const profile = user.profile as Record<string, any>;
    const identityProvider = (profile["identity_provider"] as string)?.toLowerCase();

    return {
        username:
            profile["idir_username"] ?? profile["bceid_username"] ?? undefined,
        displayName: profile["display_name"] ?? profile["name"],
        email: profile["email"],
        idpProvider: identityProvider,
        organization: profile["bceid_business_name"],
    };
};

/**
 * Completes the sign-in redirect, provisions the FAM user and starts the session.
 */
const handlePostLogin = async () => {
    try {
        isLoading.value = true;

        // Exchanges the authorization code and stores the resulting user.
        const user = await getUserManager().signinRedirectCallback();
        applySession(user);

        // Replaces the Cognito pre-token trigger: creates the fam_user row and
        // resolves roles. Must happen before any other API call.
        await bootstrapLogin();

        startSilentRefresh();
        resetInactivityTimeout();

        /*
         * Remove the 'authCallback' part from the browser URL without disrupting
         * Vue Router's state, so the fragment does not linger and the auth guard
         * has one fewer comparison to make.
         */
        const newUrl = window.location.href.replace(
            new RegExp(`${AUTH_CALLBACK_PATH.replace("/", "")}.*`),
            ""
        );
        history.replaceState(null, "", newUrl);

        router.push("/manage-permissions");
    } catch (error) {
        console.log("Authentication Error:", error);
        logout();
    } finally {
        isLoading.value = false;
    }
};

/**
 * Restores the user's session on page reload if the user is already signed in.
 */
const restoreSession = async () => {
    try {
        isLoading.value = true;
        await loadUser();
        startSilentRefresh();
        resetInactivityTimeout();
    } catch (error) {
        console.warn(error);
        authState.value = {
            isAuthenticated: false,
            famLoginUser: null,
            isAuthRestored: true,
        };
    } finally {
        isLoading.value = false;
    }
};

/**
 * Publishes an authenticated session: sets the axios bearer header and the
 * shared auth state.
 */
const applySession = (user: User) => {
    authState.value = {
        isAuthenticated: true,
        famLoginUser: getFamLoginUser(user),
        isAuthRestored: true,
    };
    setAxiosAuthorizationHeader(user.access_token);
};

/**
 * Loads the stored user, renewing silently when the access token has expired.
 *
 * `signinSilent` uses the refresh token; it does not need a hidden iframe, so it
 * is unaffected by third-party cookie restrictions.
 */
const loadUser = async (): Promise<void> => {
    const manager = getUserManager();

    let user = await manager.getUser();
    if (!user || user.expired) {
        user = await manager.signinSilent();
    }

    if (!user || !user.access_token) {
        throw new Error("The user is not authenticated");
    }

    applySession(user);

    // Re-read identity and roles so a permission change is picked up without a
    // fresh sign-in. Roles live in the database now, not in the token.
    await fetchSelf();
};

/**
 * Prevents concurrent token refresh attempts, reducing potential race conditions.
 */
let isRefreshing = false;

/**
 * Starts silent token refresh process for the authenticated user.
 */
const startSilentRefresh = () => {
    if (refreshIntervalId) clearInterval(refreshIntervalId);

    refreshIntervalId = setInterval(async () => {
        if (isRefreshing) return;
        try {
            isRefreshing = true;
            await loadUser();
        } catch (error) {
            console.error("Silent refresh failed:", error);
            logout();
        } finally {
            isRefreshing = false;
        }
    }, REFRESH_INTERVAL) as unknown as number;
};

/**
 * Creates a debounced version of the provided function, ensuring it is not
 * called more frequently than the specified delay.
 *
 * Used so `resetInactivityTimeout` is not triggered on every mouse move.
 */
const debounce = <T extends (...args: any[]) => void>(
    func: T,
    delay: number
): T => {
    let timer: ReturnType<typeof setTimeout> | null = null;

    return ((...args: Parameters<T>) => {
        if (timer) clearTimeout(timer);
        timer = setTimeout(() => func(...args), delay);
    }) as T;
};

const debouncedResetInactivityTimeout = debounce(
    resetInactivityTimeout,
    ONE_SECOND
);

/**
 * Lifecycle hook that runs when the component is mounted.
 * - Adds event listeners for user activity to reset the inactivity timeout.
 * - On the `/authCallback` path, completes the sign-in.
 * - Otherwise, attempts to restore an existing session.
 */
onMounted(() => {
    window.addEventListener("mousemove", debouncedResetInactivityTimeout);
    window.addEventListener("keydown", debouncedResetInactivityTimeout);
    window.addEventListener("click", debouncedResetInactivityTimeout);

    const currentPath = window.location.pathname;
    if (currentPath === AUTH_CALLBACK_PATH) {
        handlePostLogin();
    } else {
        restoreSession();
    }
});

/**
 * Lifecycle hook that runs when the component is about to be unmounted.
 */
onBeforeUnmount(() => {
    stopSilentRefresh();

    if (inactivityTimeoutId) {
        clearTimeout(inactivityTimeoutId);
    }

    window.removeEventListener("mousemove", debouncedResetInactivityTimeout);
    window.removeEventListener("keydown", debouncedResetInactivityTimeout);
    window.removeEventListener("click", debouncedResetInactivityTimeout);
});

/**
 * Provides authentication state and functions for use in components.
 */
provide<AuthContext>(AUTH_KEY, {
    get authState() {
        return readonly(authState.value);
    },
    login,
    logout,
    handlePostLogin,
});
</script>

<template>
    <div v-if="isLoading" class="auth-callback-container">
        <Spinner loading-text="Page loading" />
    </div>
    <slot v-else />
</template>

<style lang="scss" scoped>
.auth-callback-container {
    display: flex;
    justify-content: center;
    align-items: center;
    height: 100vh;
    width: 100vw;
}
</style>
