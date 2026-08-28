import { describe, expect, it } from "vitest";
import type { FamDistrictDto, FamForestClientDto, FamRegionDto } from "fam-api";
import type { PermissionGroup } from "@/components/PermissionsTable/utils";
import type { RoleScopeSelection } from "@/utils/ScopeUtils";
import type { KnownNames } from "./editUtils";
import {
    combinationKey,
    diffScopes,
    forestClientNumbers,
    isNoop,
    plannedGrants,
    toSelection,
    withResolvedNames,
} from "./editUtils";

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

describe("forestClientNumbers", () => {
    it("lists the organisations a grant is held for, once each", () => {
        // A compound role repeats them: granted for two districts against one
        // organisation, that organisation is in both combinations and would
        // otherwise be looked up twice.
        const held = group([
            [scope("DISTRICT", "DCC"), scope("FOREST_CLIENT", "00001012")],
            [scope("DISTRICT", "DMH"), scope("FOREST_CLIENT", "00001012")],
            [scope("DISTRICT", "DMH"), scope("FOREST_CLIENT", "00002045")],
        ]);

        expect(forestClientNumbers(held)).toEqual(["00001012", "00002045"]);
    });

    it("is empty for a grant with no organisation in it", () => {
        expect(forestClientNumbers(group([[scope("REGION", "SKEENA")]]))).toEqual([]);
    });
});

/**
 * Naming what a grant is held for, once the lists it is named from arrive.
 *
 * A held scope opens as a bare code or number. The page used to seed the form
 * the moment the grant itself loaded and never revisit it, so a district list
 * that arrived a moment later left the form reading DCC where every other screen
 * reads a name - and an organisation, whose name is fetched a number at a time,
 * showed a number with an empty name and an empty status while the same one
 * added through the picker showed both.
 */
describe("withResolvedNames", () => {
    const DCC = { org_unit_code: "DCC", org_unit_name: "Cariboo-Chilcotin" } as FamDistrictDto;
    const SKEENA = { region_code: "SKEENA", region_name: "Skeena" } as FamRegionDto;
    const ACME: FamForestClientDto = {
        forest_client_number: "00001012",
        client_name: "ACME FORESTRY LTD",
        status: { status_code: "A", description: "Active" },
    };

    const known = (over: Partial<KnownNames> = {}): KnownNames => ({
        districts: new Map(),
        regions: new Map(),
        clients: new Map(),
        ...over,
    });

    const selection = (over: Partial<RoleScopeSelection> = {}) =>
        [
            {
                role: ROLE,
                districts: [],
                regions: [],
                forestClients: [],
                ...over,
            },
        ] as RoleScopeSelection[];

    it("gives a district its name when the list arrives after the form", () => {
        /*
            The list is fetched alongside the grant and can land second. The
            form seeded from a code has to pick the name up, or it reads DCC for
            the rest of the session.
        */
        const held = selection({
            districts: [{ org_unit_code: "DCC", org_unit_name: "DCC" } as FamDistrictDto],
        });

        const named = withResolvedNames(held, known({ districts: new Map([["DCC", DCC]]) }));

        expect(named[0].districts[0]).toBe(DCC);
    });

    it("gives a region its name the same way", () => {
        const held = selection({
            regions: [{ region_code: "SKEENA", region_name: "SKEENA" } as FamRegionDto],
        });

        const named = withResolvedNames(held, known({ regions: new Map([["SKEENA", SKEENA]]) }));

        expect(named[0].regions[0]).toBe(SKEENA);
    });

    it("gives a held organisation its name and status", () => {
        const named = withResolvedNames(
            selection({
                forestClients: [{ forest_client_number: "00001012" } as FamForestClientDto],
            }),
            known({ clients: new Map([["00001012", ACME]]) })
        );

        expect(named[0].forestClients[0]).toBe(ACME);
    });

    it("leaves an organisation that already has a name alone", () => {
        // It came from the picker, which answers with the whole record. Looking
        // it up again could only replace it with the same thing.
        const chosen = selection({ forestClients: [ACME] });

        expect(
            withResolvedNames(chosen, known({ clients: new Map([["00001012", { ...ACME }]]) }))
        ).toBe(chosen);
    });

    it("keeps a scope nothing names", () => {
        /*
            A district retired from the reference set, an organisation
            deactivated since it was granted, or a list that never arrived. It
            is still a scope the person holds, and dropping it here would
            quietly revoke it on save.
        */
        const held = selection({
            districts: [{ org_unit_code: "DZZ", org_unit_name: "DZZ" } as FamDistrictDto],
            forestClients: [{ forest_client_number: "00009999" } as FamForestClientDto],
        });

        const named = withResolvedNames(
            held,
            known({
                districts: new Map([["DCC", DCC]]),
                clients: new Map([["00001012", ACME]]),
            })
        );

        expect(named[0].districts[0].org_unit_code).toBe("DZZ");
        expect(named[0].forestClients[0]).toEqual({ forest_client_number: "00009999" });
    });

    it("does not reinstate a scope that was removed while the list was loading", () => {
        /*
            Why this names the current selection rather than rebuilding it from
            the grant. Rebuilding would put back the region somebody had just
            taken off, and the save would silently re-grant it.
        */
        const afterRemoval = selection({ regions: [] });

        const named = withResolvedNames(
            afterRemoval,
            known({ regions: new Map([["SKEENA", SKEENA]]) })
        );

        expect(named[0].regions).toEqual([]);
    });

    it("returns what it was given when nothing resolved", () => {
        /*
            The page sets state with this on every change to any of the lists. A
            new array each time would queue a render that changes nothing, and
            the effect that does it would never settle.
        */
        const held = selection({
            forestClients: [{ forest_client_number: "00009999" } as FamForestClientDto],
        });

        expect(withResolvedNames(held, known())).toBe(held);
        expect(withResolvedNames(held, known({ clients: new Map([["00001012", ACME]]) }))).toBe(
            held
        );
    });

    it("settles on a second pass over what it just named", () => {
        // The reference sets are authoritative, so entries are replaced rather
        // than merged - which would loop if it never recognised its own work.
        const named = withResolvedNames(
            selection({
                districts: [{ org_unit_code: "DCC", org_unit_name: "DCC" } as FamDistrictDto],
            }),
            known({ districts: new Map([["DCC", DCC]]) })
        );

        expect(withResolvedNames(named, known({ districts: new Map([["DCC", DCC]]) }))).toBe(
            named
        );
    });
});
