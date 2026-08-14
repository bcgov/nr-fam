import { authState } from "@/providers/authState";
import { isFamAdmin } from "@/utils/AdminRoleUtils";
import type { RouteLocationNormalized, NavigationGuardNext } from "vue-router";

/**
 * Waits for the authentication state to be restored.
 * @returns {Promise<void>}
 */
const waitForAuthRestoration = (): Promise<void> => {
    return new Promise((resolve) => {
        const checkAuthRestored = () => {
            if (authState.value.isAuthRestored) {
                resolve();
            } else {
                setTimeout(checkAuthRestored, 100);
            }
        };
        checkAuthRestored();
    });
};

/**
 * Checks if the user is authenticated based on the current authentication state.
 * @returns {boolean} True if the user is authenticated, otherwise false.
 */
const isAuthenticated = (): boolean => {
    return authState.value.isAuthenticated;
};

/**
 * Auth guard that manages navigation based on authentication state.
 * Redirects unauthenticated users to the landing page.
 */
export const authGuard = async (
    _to: RouteLocationNormalized,
    _from: RouteLocationNormalized,
    next: NavigationGuardNext
) => {
    await waitForAuthRestoration();

    if (isAuthenticated()) {
        next();
    } else {
        next({ path: "/" });
    }
};

/**
 * Guard for the screens only a FAM administrator may open.
 *
 * A convenience, not a control: it stops somebody who typed the URL from landing
 * on a screen whose every call would fail. What actually protects the operation
 * is the backend, which re-checks the caller's roles on the request itself.
 *
 * Sends a signed-in non-administrator to `/no-access` rather than the landing
 * page, which would bounce them back here through `landingGuard`.
 */
export const famAdminGuard = async (
    _to: RouteLocationNormalized,
    _from: RouteLocationNormalized,
    next: NavigationGuardNext
) => {
    await waitForAuthRestoration();

    if (!isAuthenticated()) {
        next({ path: "/" });
    } else if (isFamAdmin()) {
        next();
    } else {
        next({ path: "/no-access" });
    }
};

/**
 * Redirects authenticated users to `/manage-permissions` if they attempt to access the landing page.
 */
export const landingGuard = async (
    _to: RouteLocationNormalized,
    _from: RouteLocationNormalized,
    next: NavigationGuardNext
) => {
    await waitForAuthRestoration();

    if (isAuthenticated()) {
        next("/manage-permissions");
    } else {
        next();
    }
};
