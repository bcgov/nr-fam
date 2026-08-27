import { describe, expect, it } from "vitest";
import { compareRoleKeys, roleOptionKey, sortedByRole } from "./RoleSort";

/**
 * One ordering for every table that shows or offers roles.
 *
 * CSS returns roles in an order that is neither alphabetical nor stable, so the
 * same application listed them differently between two visits and finding one
 * meant reading the whole column.
 */
describe("roleOptionKey", () => {
    it("prefers the machine code beneath a display role", () => {
        expect(
            roleOptionKey({ name: "FAM:LABEL:FSPTS_VIEW", role_code: "FSPTS_VIEW" })
        ).toBe("FSPTS_VIEW");
    });

    it("falls back to the name for a role with no code", () => {
        expect(roleOptionKey({ name: "FREP_EDITOR" })).toBe("FREP_EDITOR");
    });
});

describe("compareRoleKeys", () => {
    it("orders alphabetically", () => {
        expect(compareRoleKeys("FREP_ADMIN", "FREP_EDITOR")).toBeLessThan(0);
    });

    it("orders trailing numbers by value, not by digit", () => {
        // Plain string ordering puts TIER_10 before TIER_2, which reads as a
        // sorting fault rather than a convention.
        expect(compareRoleKeys("TIER_2", "TIER_10")).toBeLessThan(0);
    });

    it("does not sort lower case into a group of its own", () => {
        // CSS does not enforce a case, so one lower-case role should not land
        // in an exile after every upper-case one.
        expect(compareRoleKeys("frep_editor", "FREP_VIEWER")).toBeLessThan(0);
    });
});

describe("sortedByRole", () => {
    // Built per case rather than shared: an in-place sort in one test would
    // leave the next one asserting against an array that was already ordered,
    // which is exactly the bug this file is meant to catch.
    const unsorted = () => [
        { name: "FREP_VIEWER" },
        { name: "FREP_EDITOR" },
        { name: "FREP_ADMIN" },
    ];

    it("puts them in code order whatever order they arrived in", () => {
        expect(sortedByRole(unsorted(), roleOptionKey).map((r) => r.name)).toEqual([
            "FREP_ADMIN",
            "FREP_EDITOR",
            "FREP_VIEWER",
        ]);
    });

    it("leaves the caller's array alone", () => {
        // The input is a react-query cache entry: it is shared, and nothing
        // else expects it to be reordered underneath.
        const roles = unsorted();
        const original = [...roles];
        sortedByRole(roles, roleOptionKey);
        expect(roles).toEqual(original);
    });

    it("sorts a row that has no key at all to the front rather than throwing", () => {
        const rows = [{ code: "B" }, { code: undefined }, { code: "A" }];
        expect(
            sortedByRole(rows, (row) => row.code).map((row) => row.code)
        ).toEqual([undefined, "A", "B"]);
    });
});
