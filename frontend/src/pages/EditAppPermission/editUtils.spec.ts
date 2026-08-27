import { describe, expect, it } from "vitest";
import type { PermissionGroup } from "@/components/PermissionsTable/utils";
import { combinationKey, diffScopes, isNoop, plannedGrants, toSelection } from "./editUtils";

/**
 * Reading a grant back into the form that would have made it.
 *
 * The grant screen writes selections out into CSS role names; editing is the
 * only thing that needs the journey in the other direction, and it has to be
 * exact - a scope this fails to recover is one the person silently loses when
 * they save.
 */

const scope = (type: string, value: string) => ({ type, value });

const group = (combinations: { type: string; value: string }[][]): PermissionGroup =>
    ({
        assignments: combinations.map((_, index) => ({
            username: "JSMITH",
            role_name: "FREP_EDITOR",
            scopes: combinations[index],
        })),
        combinations,
    }) as never;

const ROLE = { name: "FREP_EDITOR" } as never;

describe("toSelection", () => {
    it("names a district from the real list rather than its code", () => {
        // Every other row in that picker reads as a name; one reading DCC would
        // look like a row that failed to load.
        const selection = toSelection(
            group([[scope("DISTRICT", "DCC")]]),
            ROLE,
            [{ org_unit_code: "DCC", org_unit_name: "Cariboo-Chilcotin" }] as never,
            []
        );

        expect(selection.districts).toEqual([
            { org_unit_code: "DCC", org_unit_name: "Cariboo-Chilcotin" },
        ]);
    });

    it("keeps a code the list does not know", () => {
        // A district retired since the grant. Dropping it would quietly revoke
        // access the person still holds the moment they saved.
        const selection = toSelection(
            group([[scope("DISTRICT", "DGONE")]]),
            ROLE,
            [],
            []
        );

        expect(selection.districts).toHaveLength(1);
        expect(selection.districts[0].org_unit_code).toBe("DGONE");
    });

    it("lists a shared organisation once, not once per pairing", () => {
        // A compound role repeats it in every combination; the picker holds it
        // once.
        const selection = toSelection(
            group([
                [scope("DISTRICT", "DCC"), scope("FOREST_CLIENT", "001")],
                [scope("DISTRICT", "DKA"), scope("FOREST_CLIENT", "001")],
            ]),
            ROLE,
            [],
            []
        );

        expect(selection.forestClients).toHaveLength(1);
        expect(selection.districts).toHaveLength(2);
    });

    it("sorts each kind into its own picker", () => {
        const selection = toSelection(
            group([[scope("REGION", "SKEENA")]]),
            ROLE,
            [{ org_unit_code: "DCC", org_unit_name: "Cariboo" }] as never,
            [{ region_code: "SKEENA", region_name: "Skeena" }] as never
        );

        expect(selection.regions).toEqual([
            { region_code: "SKEENA", region_name: "Skeena" },
        ]);
        expect(selection.districts).toEqual([]);
    });
});

describe("combinationKey", () => {
    it("reads a pairing the same however it arrives", () => {
        // The role name orders its suffixes, but nothing guarantees the order
        // they come back in - and a pairing counted twice is a grant made twice.
        expect(
            combinationKey([scope("DISTRICT", "DCC"), scope("FOREST_CLIENT", "001")])
        ).toBe(
            combinationKey([scope("FOREST_CLIENT", "001"), scope("DISTRICT", "DCC")])
        );
    });

    it("tells different pairings apart", () => {
        expect(combinationKey([scope("DISTRICT", "DCC")])).not.toBe(
            combinationKey([scope("DISTRICT", "DKA")])
        );
    });
});

describe("diffScopes", () => {
    it("asks for nothing when nothing changed", () => {
        const held = group([[scope("REGION", "SKEENA")]]);

        expect(diffScopes(held, [[scope("REGION", "SKEENA")]])).toEqual({
            added: [],
            removed: [],
        });
    });

    it("grants only what is new", () => {
        const held = group([[scope("REGION", "SKEENA")]]);

        const diff = diffScopes(held, [
            [scope("REGION", "SKEENA")],
            [scope("REGION", "NORTHEAST")],
        ]);

        expect(diff.added).toEqual([[scope("REGION", "NORTHEAST")]]);
        expect(diff.removed).toEqual([]);
    });

    it("revokes only what was dropped", () => {
        const held = group([
            [scope("REGION", "SKEENA")],
            [scope("REGION", "NORTHEAST")],
        ]);

        const diff = diffScopes(held, [[scope("REGION", "SKEENA")]]);

        expect(diff.added).toEqual([]);
        expect(diff.removed).toHaveLength(1);
        expect(diff.removed[0].scopes).toEqual([scope("REGION", "NORTHEAST")]);
    });

    it("leaves untouched scopes alone", () => {
        /*
            The reason this is a diff and not a replace. Revoking everything and
            granting it back would take away access the edit never mentioned,
            and would do so for real in the window between the two.
        */
        const held = group([
            [scope("REGION", "SKEENA")],
            [scope("REGION", "NORTHEAST")],
            [scope("REGION", "CARIBOO")],
        ]);

        const diff = diffScopes(held, [
            [scope("REGION", "SKEENA")],
            [scope("REGION", "CARIBOO")],
        ]);

        expect(diff.added).toEqual([]);
        expect(diff.removed).toHaveLength(1);
    });

    it("matches a pairing whichever order its scopes arrive in", () => {
        const held = group([
            [scope("DISTRICT", "DCC"), scope("FOREST_CLIENT", "001")],
        ]);

        const diff = diffScopes(held, [
            [scope("FOREST_CLIENT", "001"), scope("DISTRICT", "DCC")],
        ]);

        expect(diff).toEqual({ added: [], removed: [] });
    });
});

/**
 * What actually gets sent, once the diff and the expiry are both accounted for.
 *
 * This is the half a diff cannot see. An expiry change moves no combination, so
 * on the diff alone the edit looks like nothing happened - which is exactly what
 * it did: the form reported success over a date that was never applied.
 */
describe("plannedGrants", () => {
    const combo = (region: string) => [{ type: "REGION", value: region }];
    const wanted = [combo("SKEENA"), combo("NORTHEAST")];

    it("sends only the new combinations when the date has not moved", () => {
        const diff = { added: [combo("NORTHEAST")], removed: [] };

        expect(plannedGrants(diff, wanted, false)).toEqual([combo("NORTHEAST")]);
    });

    it("re-issues every kept combination when the date has moved", () => {
        /*
            A role carries one expiry marker and granting replaces it, so
            re-granting is how the date is changed. Sending only the new ones
            would leave the rest on the old date - one role ending on two
            different days, and two rows the next time the table loaded.
        */
        const diff = { added: [], removed: [] };

        expect(plannedGrants(diff, wanted, true)).toEqual(wanted);
    });

    it("sends nothing at all when nothing changed", () => {
        expect(plannedGrants({ added: [], removed: [] }, wanted, false)).toEqual([]);
    });
});

describe("isNoop", () => {
    it("is true only when neither the scopes nor the date moved", () => {
        expect(isNoop({ added: [], removed: [] }, false)).toBe(true);
    });

    it("is false for a date change on its own", () => {
        // The case that used to report "nothing to change" over a real edit.
        expect(isNoop({ added: [], removed: [] }, true)).toBe(false);
    });

    it("is false when a scope was added or dropped", () => {
        expect(
            isNoop({ added: [[{ type: "REGION", value: "SKEENA" }]], removed: [] }, false)
        ).toBe(false);
        expect(isNoop({ added: [], removed: [{} as never] }, false)).toBe(false);
    });
});

