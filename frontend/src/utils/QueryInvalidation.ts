/**
 * Only what these functions use, rather than either library's QueryClient.
 *
 * During the migration both are installed, and they resolve to different copies
 * of query-core - so a client from `@tanstack/vue-query` is not assignable to
 * the type from `@tanstack/react-query` even though it is the same object at
 * runtime. Typing the parameter by the one method called here lets the Vue
 * screens and the React ones share this file, which is the point of it: the
 * list of what goes stale is what drifts, and it should exist once.
 *
 * Drop this for the real QueryClient once the last Vue screen is gone.
 */
type InvalidatingClient = {
    invalidateQueries: (filters: {
        queryKey: unknown[];
        refetchType?: "active" | "inactive" | "all" | "none";
    }) => unknown;
};

/**
 * Everything that goes stale when somebody's access changes.
 *
 * Each mutation used to invalidate the one query behind the screen it was on.
 * That is correct for what you are looking at and wrong for everything else: a
 * revocation refreshed the users table and left the audit history for that same
 * person showing the grant as current. With `staleTime` at three hours, a
 * history already opened once stayed that way until the tab was reloaded.
 *
 * Gathered here rather than repeated because the list is the part that goes out
 * of date - a new screen adds a query, and every mutation that should refresh it
 * is somewhere else entirely.
 *
 * <b>Marking stale is enough.</b> These queries set `refetchOnMount: true`, and
 * a stale query refetches when it next mounts. Forcing a refetch of every cached
 * history for every user would be a burst of requests for screens nobody is
 * looking at.
 */
export const invalidateAfterAccessChange = (
    queryClient: InvalidatingClient,
    integrationId?: number,
    environment?: string
): void => {
    // Scoped to the application when we know it. The administrators key carries
    // a tier on the end, which a prefix match covers.
    if (integrationId !== undefined && environment !== undefined) {
        for (const key of ["css-user-role-assignments", "css-administrators"]) {
            queryClient.invalidateQueries({
                queryKey: [key, integrationId, environment],
                // The table is often not mounted yet - a grant redirects to it -
                // so ask for the refetch outright rather than depending on
                // options set in another file.
                refetchType: "all",
            });
        }
    }

    // Not scoped: the history is keyed by target user as well, and a change to
    // one person's access can be viewed from a history opened for any of them.
    // Whichever entries are cached, they are now wrong.
    queryClient.invalidateQueries({ queryKey: ["permission-audit-history"] });

    // What the caller themselves may do. A delegated admin's own delegation can
    // be withdrawn while they are on the screen, and the side nav is drawn from
    // these.
    queryClient.invalidateQueries({ queryKey: ["self-permissions"] });
    queryClient.invalidateQueries({ queryKey: ["self-application-roles"] });
};

/**
 * Everything that goes stale when an application's roles change.
 *
 * A role is not access, but deleting one takes access with it - the derived
 * per-scope roles go, the people holding them lose them, and any delegation
 * naming it is withdrawn. So this is the access list plus the two role queries.
 */
export const invalidateAfterRoleChange = (
    queryClient: InvalidatingClient,
    integrationId?: number,
    environment?: string
): void => {
    if (integrationId !== undefined && environment !== undefined) {
        for (const key of ["css-roles", "css-role-member-counts"]) {
            queryClient.invalidateQueries({
                queryKey: [key, integrationId, environment],
                refetchType: "all",
            });
        }
    }
    invalidateAfterAccessChange(queryClient, integrationId, environment);
};
