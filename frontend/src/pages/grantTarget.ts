import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { useSelectedApp } from "@/context/application/useSelectedApp";
import { isPermissionsTab } from "@/pages/ManagePermissions/utils";
import { ROUTES } from "@/routes/routePaths";
import { AdminMgmtApiService } from "@/services/ApiServiceFactory";

/**
 * Which application a grant screen is acting on.
 *
 * Carried on the query string rather than read from the selected-application
 * context, because a link to one of these screens has to work on its own - the
 * context is empty on a fresh page load, and a form that silently granted
 * against the wrong application would be worse than one that fails to load.
 */
export type GrantTarget = {
    integrationId: number;
    environment: string;
};

export const useGrantTarget = (): GrantTarget => {
    const [params] = useSearchParams();
    return {
        // NaN when absent, which the guard below turns into a redirect rather
        // than a request for integration "NaN".
        integrationId: Number(params.get("integrationId")),
        environment: params.get("environment") ?? "",
    };
};

export const isGrantTargetValid = (target: GrantTarget): boolean =>
    Number.isFinite(target.integrationId) &&
    target.integrationId > 0 &&
    target.environment.length > 0;

/**
 * What to call the application a grant screen is acting on.
 *
 * Answers `"FREP (DEV)"` - the backend composes `description` as
 * `<name> (<ENV>)`, so this is the same string the picker on Manage permissions
 * showed, and the two cannot drift into describing one application two ways.
 *
 * The selected-application context is preferred but not relied on: it is empty
 * on a fresh load, so a bookmarked or refreshed grant screen used to fall back
 * to the bare environment and read "Grant a role in DEV". The applications list
 * is already cached by Manage permissions, so the fallback usually costs
 * nothing - and it is only asked for when the context cannot answer.
 *
 * The context is also checked against the query string rather than trusted: the
 * URL is what the form actually grants against, so a stale context entry must
 * not be allowed to name a different application than the one being written to.
 */
export const useGrantTargetName = (): string => {
    const { integrationId, environment } = useGrantTarget();
    const { selectedApp } = useSelectedApp();

    const fromContext =
        selectedApp?.integration_id === integrationId &&
        selectedApp?.environment === environment
            ? selectedApp
            : undefined;

    const applicationsQuery = useQuery({
        queryKey: ["css-applications"],
        queryFn: () =>
            AdminMgmtApiService.cssIntegrationsApi
                .getCssApplications()
                .then((res) => res.data),
        enabled: !fromContext,
    });

    const resolved =
        fromContext ??
        (applicationsQuery.data ?? []).find(
            (app) =>
                app.integration_id === integrationId &&
                app.environment === environment
        );

    // The environment alone is a poor name, but it is true, and it is better
    // than a blank while the list loads.
    return resolved?.description ?? environment.toUpperCase();
};

/**
 * Where a grant screen goes when it is finished or abandoned.
 *
 * Manage permissions is a tabbed screen and these forms are opened from one of
 * its tabs, so returning to the bare route dropped somebody who had just
 * appointed a DevOps administrator onto the list of ordinary users, with no sign
 * of what they had done. The tab travels out on the query string, alongside the
 * application, and comes back the same way.
 *
 * Falls back to the bare route for a screen reached without one - a bookmark, or
 * a link written by hand - which lands on Users, as it always did.
 */
export const useManagePermissionsReturn = (): string => {
    const [params] = useSearchParams();
    const tab = params.get("tab");

    return isPermissionsTab(tab)
        ? `${ROUTES.managePermissions}?tab=${tab}`
        : ROUTES.managePermissions;
};
