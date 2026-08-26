import type { FC, ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "@/context/auth/useAuth";
import { ROUTES } from "./routePaths";

/**
 * Route guards, as components rather than navigation hooks.
 *
 * The Vue guards polled `isAuthRestored` every 100ms before deciding. React
 * re-renders when it changes, so the wait is just "do not decide yet" - no
 * timer, and no window where a guard judges a session that has not finished
 * loading. That window is what once sent a FAM administrator to /no-access on
 * a refresh, because the roles had not arrived when the guard read them.
 *
 * None of these is a control. They stop somebody who typed a URL from landing
 * on a screen whose every call would fail; the backend re-checks the caller's
 * roles on the request itself.
 */

const FAM_ADMIN = "FAM_ADMIN";

/** Renders nothing until the session has been judged. */
const useSettledAuth = () => {
    const { authState } = useAuth();
    return {
        settled: authState.isAuthRestored,
        isAuthenticated: authState.isAuthenticated,
        isFamAdmin: authState.accessRoles.includes(FAM_ADMIN),
    };
};

/** Signed in, or back to the landing page. */
export const RequireAuth: FC<{ children: ReactNode }> = ({ children }) => {
    const { settled, isAuthenticated } = useSettledAuth();
    if (!settled) {
        return null;
    }
    return isAuthenticated ? <>{children}</> : <Navigate to={ROUTES.landing} replace />;
};

/**
 * FAM administrators only.
 *
 * A signed-in non-administrator goes to /no-access rather than the landing
 * page, which would bounce them straight back here through RedirectIfSignedIn.
 */
export const RequireFamAdmin: FC<{ children: ReactNode }> = ({ children }) => {
    const { settled, isAuthenticated, isFamAdmin } = useSettledAuth();
    if (!settled) {
        return null;
    }
    if (!isAuthenticated) {
        return <Navigate to={ROUTES.landing} replace />;
    }
    return isFamAdmin ? <>{children}</> : <Navigate to={ROUTES.noAccess} replace />;
};

/** The landing page is for people who are not signed in. */
export const RedirectIfSignedIn: FC<{ children: ReactNode }> = ({ children }) => {
    const { settled, isAuthenticated } = useSettledAuth();
    if (!settled) {
        return null;
    }
    return isAuthenticated ? (
        <Navigate to={ROUTES.managePermissions} replace />
    ) : (
        <>{children}</>
    );
};
