import type { FC, ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { ROUTES } from "@/routes/routePaths";
import { isGrantTargetValid, useGrantTarget } from "./grantTarget";

/**
 * Sends somebody back when the URL does not name an application.
 *
 * These screens are only ever reached from Manage permissions, which puts the
 * integration and environment on the query string. Arriving without them - a
 * bookmark saved before the application was chosen, or a hand-edited URL - would
 * otherwise load a form that requests roles for integration "NaN" and fails in a
 * way that reads as a broken screen.
 */
export const RequireGrantTarget: FC<{ children: ReactNode }> = ({ children }) => {
    const target = useGrantTarget();
    return isGrantTargetValid(target) ? (
        <>{children}</>
    ) : (
        <Navigate to={ROUTES.managePermissions} replace />
    );
};

export default RequireGrantTarget;
