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

    it("withholds it from somebody who administers nothing", () => {
        // It used to hand them Manage permissions, where an empty selector
        // reported a failure that had not happened. They no longer reach the
        // menu at all - RequireAnyFamRole sends them to /no-access before the
        // shell renders - so what is left here is only that nothing invites
        // them back to that screen.
        expect(ids([])).not.toContain("manage-permissions");
    });
});

describe("homeRouteFor", () => {
    it("starts a DevOps-only administrator on Manage roles", () => {
        // Manage permissions is hidden from them; landing them there would be a
        // screen with nothing on it and no way back to it.
        expect(homeRouteFor(["DEVOPS_ADMIN_6538_DEV"])).toBe(ROUTES.manageRoles);
    });

    it("starts every other administrator on Manage permissions", () => {
        expect(homeRouteFor(["FAM_ADMIN"])).toBe(ROUTES.managePermissions);
        expect(homeRouteFor(["APP_ADMIN_6538_DEV"])).toBe(ROUTES.managePermissions);
        expect(homeRouteFor(["DELEGATED_ADMIN_6538_DEV"])).toBe(
            ROUTES.managePermissions
        );
    });

    it("sends somebody with no role to the page that says so", () => {
        // Not to a screen that will load and then fail in front of them.
        expect(homeRouteFor([])).toBe(ROUTES.noAccess);
        expect(homeRouteFor(["SOME_APPLICATION_ROLE"])).toBe(ROUTES.noAccess);
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

