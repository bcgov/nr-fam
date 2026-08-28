import { describe, expect, it } from "vitest";
import { getMenuEntries, homeRouteFor, ROUTES } from "./routePaths";

/**
 * Which screens a set of roles admits, and where somebody starts.
 *
 * The two answers have to agree: sending somebody to a screen the menu hides
 * lands them somewhere with no nav entry pointing back at it.
 */

const ids = (roles: string[]) => getMenuEntries(roles).map((entry) => entry.id);

describe("getMenuEntries", () => {
    it("withholds Manage permissions from a DevOps-only administrator", () => {
        // They manage no access, so that screen is empty for them.
        expect(ids(["DEVOPS_ADMIN_6538_DEV"])).not.toContain("manage-permissions");
        expect(ids(["DEVOPS_ADMIN_6538_DEV"])).toContain("manage-roles");
    });

    it("keeps it for a DevOps admin who administers access as well", () => {
        expect(
            ids(["DEVOPS_ADMIN_6538_DEV", "DELEGATED_ADMIN_6538_DEV"])
        ).toContain("manage-permissions");
    });

    it("keeps it for somebody who administers nothing", () => {
        // An empty table is a fair answer to "what do I administer".
        expect(ids([])).toContain("manage-permissions");
    });
});

describe("homeRouteFor", () => {
    it("starts a DevOps-only administrator on Manage roles", () => {
        // Manage permissions is hidden from them; landing them there would be a
        // screen with nothing on it and no way back to it.
        expect(homeRouteFor(["DEVOPS_ADMIN_6538_DEV"])).toBe(ROUTES.manageRoles);
    });

    it("starts everybody else on Manage permissions", () => {
        expect(homeRouteFor(["FAM_ADMIN"])).toBe(ROUTES.managePermissions);
        expect(homeRouteFor(["APP_ADMIN_6538_DEV"])).toBe(ROUTES.managePermissions);
        expect(homeRouteFor([])).toBe(ROUTES.managePermissions);
    });
});

describe("User history visibility", () => {
    it("is offered to every tier that administers access", () => {
        // The screen asks about one application at a time and shows what has
        // happened to access the caller already manages.
        for (const role of [
            "FAM_ADMIN",
            "APP_ADMIN_6538_DEV",
            "DELEGATED_ADMIN_6538_DEV",
        ]) {
            expect(ids([role])).toContain("user-history");
        }
    });

    it("is withheld from a DevOps-only administrator", () => {
        // They administer no access, so every application picker is empty.
        expect(ids(["DEVOPS_ADMIN_6538_DEV"])).not.toContain("user-history");
    });
});

