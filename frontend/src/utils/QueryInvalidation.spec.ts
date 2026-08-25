import { describe, expect, it, vi } from "vitest";
import {
    invalidateAfterAccessChange,
    invalidateAfterRoleChange,
} from "./QueryInvalidation";

/**
 * What goes stale when access changes.
 *
 * The bug this exists for: revoking a permission refreshed the users table and
 * left the audit history for that same person still showing the grant. Nothing
 * invalidated the history, and with staleTime at three hours a history already
 * opened once stayed wrong until the tab was reloaded.
 */
const clientWithSpy = () => {
    const invalidateQueries = vi.fn();
    return { queryClient: { invalidateQueries } as never, invalidateQueries };
};

/** The first element of every key the call invalidated. */
const familiesInvalidated = (spy: { mock: { calls: any[][] } }) =>
    spy.mock.calls.map((call) => call[0].queryKey[0]);

describe("invalidateAfterAccessChange", () => {
    it("invalidates the audit history", () => {
        const { queryClient, invalidateQueries } = clientWithSpy();

        invalidateAfterAccessChange(queryClient, 6538, "dev");

        expect(familiesInvalidated(invalidateQueries)).toContain(
            "permission-audit-history"
        );
    });

    it("invalidates the history for every user, not one", () => {
        // The history is keyed by target user too. A revocation can be viewed
        // from a history opened for anybody, and we do not know which entries
        // are cached - so the whole family goes.
        const { queryClient, invalidateQueries } = clientWithSpy();

        invalidateAfterAccessChange(queryClient, 6538, "dev");

        const history = invalidateQueries.mock.calls.find(
            (call) => call[0].queryKey[0] === "permission-audit-history"
        );
        expect(history![0].queryKey).toEqual(["permission-audit-history"]);
    });

    it("marks the history stale rather than refetching it", () => {
        // Those queries set refetchOnMount, so stale is enough. Forcing a
        // refetch of every cached history would be a burst of requests for
        // screens nobody is looking at.
        const { queryClient, invalidateQueries } = clientWithSpy();

        invalidateAfterAccessChange(queryClient, 6538, "dev");

        const history = invalidateQueries.mock.calls.find(
            (call) => call[0].queryKey[0] === "permission-audit-history"
        );
        expect(history![0].refetchType).toBeUndefined();
    });

    it("refetches the tables outright", () => {
        // A grant redirects to a table that is not mounted yet, so waiting for
        // a mount that has not happened would leave it stale.
        const { queryClient, invalidateQueries } = clientWithSpy();

        invalidateAfterAccessChange(queryClient, 6538, "dev");

        const rows = invalidateQueries.mock.calls.find(
            (call) => call[0].queryKey[0] === "css-user-role-assignments"
        );
        expect(rows![0].refetchType).toBe("all");
    });

    it("invalidates the administrator rosters and the caller's own access", () => {
        const { queryClient, invalidateQueries } = clientWithSpy();

        invalidateAfterAccessChange(queryClient, 6538, "dev");

        const families = familiesInvalidated(invalidateQueries);
        expect(families).toContain("css-administrators");
        // A delegated admin's own delegation can be withdrawn while they are on
        // the screen, and the side nav is drawn from these.
        expect(families).toContain("self-permissions");
        expect(families).toContain("self-application-roles");
    });

    it("still invalidates what it can with no application named", () => {
        const { queryClient, invalidateQueries } = clientWithSpy();

        invalidateAfterAccessChange(queryClient);

        const families = familiesInvalidated(invalidateQueries);
        expect(families).toContain("permission-audit-history");
        expect(families).not.toContain("css-user-role-assignments");
    });
});

describe("invalidateAfterRoleChange", () => {
    it("invalidates the role listings as well as the access ones", () => {
        // A role is not access, but deleting one takes access with it: the
        // derived roles go, their members lose them, and any delegation naming
        // it is withdrawn.
        const { queryClient, invalidateQueries } = clientWithSpy();

        invalidateAfterRoleChange(queryClient, 6538, "dev");

        const families = familiesInvalidated(invalidateQueries);
        expect(families).toContain("css-roles");
        expect(families).toContain("css-role-member-counts");
        expect(families).toContain("css-user-role-assignments");
        expect(families).toContain("permission-audit-history");
    });
});
