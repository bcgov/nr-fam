import { UserType } from "fam-api/model";
import { describe, expect, it } from "vitest";
import {
    delegationCount,
    MAX_DELEGATIONS_PER_ROLE,
    newRoleSelection,
    requiresScope,
    roleLabel,
    rolesOverTheLimit,
    toDelegatedAdminRequests,
    totalDelegations,
    type DelegatedAdminFormType,
} from "./delegationUtils";

const role = (
    name: string,
    district = false,
    client = false,
    region = false
) =>
    ({
        name,
        display_name: null,
        description: null,
        composite: false,
        composites: [],
        role_type_district: district,
        role_type_region: region,
        role_type_client: client,
    }) as any;

const district = (code: string) => ({ org_unit_code: code }) as any;
const region = (code: string) => ({ region_code: code }) as any;
const client = (number: string) => ({ forest_client_number: number }) as any;

const form = (
    roles: DelegatedAdminFormType["roles"]
): DelegatedAdminFormType => ({
    domain: UserType.Idir,
    users: [{ userId: "JSMITH", guid: "AABB1122" } as any],
    roles,
});

const selection = (
    r: any,
    districts: any[] = [],
    forestClients: any[] = [],
    regions: any[] = []
) => ({ ...newRoleSelection(r), districts, forestClients, regions });

describe("requiresScope", () => {
    it("is false for a role delegated outright", () => {
        expect(requiresScope(role("PLAIN"))).toBe(false);
    });

    it("is true when the role is scoped any of the three ways", () => {
        expect(requiresScope(role("D", true, false))).toBe(true);
        expect(requiresScope(role("C", false, true))).toBe(true);
        expect(requiresScope(role("R", false, false, true))).toBe(true);
    });
});

describe("delegationCount", () => {
    it("is one for a role that needs no narrowing", () => {
        // Not zero: the role is still delegated, just not per anything.
        expect(delegationCount(selection(role("PLAIN")))).toBe(1);
    });

    it("is the number of values for a singly-scoped role", () => {
        expect(
            delegationCount(
                selection(role("D", true), [district("DCC"), district("DKA")])
            )
        ).toBe(2);
    });

    it("counts the regions of a region-scoped role", () => {
        expect(
            delegationCount(
                selection(role("R", false, false, true), [], [], [
                    region("CARIBOO"),
                    region("SKEENA"),
                ])
            )
        ).toBe(2);
    });

    it("multiplies region against the other dimensions too", () => {
        // Two districts, two regions and two clients is eight combinations.
        // Leaving region out of the product would say four, and understate what
        // the grant is about to create - which is the number the ceiling is
        // checked against.
        expect(
            delegationCount(
                selection(
                    role("ALL", true, true, true),
                    [district("DCC"), district("DKA")],
                    [client("00001012"), client("00001013")],
                    [region("CARIBOO"), region("SKEENA")]
                )
            )
        ).toBe(8);
    });

    it("multiplies the dimensions of a compound role", () => {
        // Three districts against two organisations is six pairs. Adding them
        // would say five, and five is not a number of anything here.
        expect(
            delegationCount(
                selection(
                    role("BOTH", true, true),
                    [district("DCC"), district("DKA"), district("DPC")],
                    [client("00001012"), client("00001013")]
                )
            )
        ).toBe(6);
    });

    it("is zero while a scoped role has nothing chosen for it", () => {
        // Which is what stops it reading as "1 delegation" before it can be
        // submitted at all.
        expect(delegationCount(selection(role("D", true)))).toBe(0);
    });

    it("ignores values for a dimension the role does not use", () => {
        // Switching a role's shape must not smuggle a stale selection into the
        // count, or the form would promise delegations it will not create.
        expect(
            delegationCount(
                selection(role("D", true), [district("DCC")], [client("00001012")])
            )
        ).toBe(1);
    });
});

describe("totalDelegations", () => {
    it("adds up across roles", () => {
        expect(
            totalDelegations(
                form([
                    selection(role("PLAIN")),
                    selection(role("D", true), [district("DCC"), district("DKA")]),
                ])
            )
        ).toBe(3);
    });
});

describe("rolesOverTheLimit", () => {
    it("names only the roles past the ceiling", () => {
        const many = Array.from({ length: MAX_DELEGATIONS_PER_ROLE + 1 }, (_, i) =>
            district(`D${i}`)
        );

        const over = rolesOverTheLimit(
            form([
                selection(role("OK", true), [district("DCC")]),
                selection(role("TOO_BIG", true), many),
            ])
        );

        expect(over.map((s) => s.role.name)).toEqual(["TOO_BIG"]);
    });

    it("allows a role sitting exactly on the ceiling", () => {
        const exactly = Array.from({ length: MAX_DELEGATIONS_PER_ROLE }, (_, i) =>
            district(`D${i}`)
        );

        expect(
            rolesOverTheLimit(form([selection(role("EDGE", true), exactly)]))
        ).toEqual([]);
    });
});

describe("toDelegatedAdminRequests", () => {
    it("makes one request per role", () => {
        const requests = toDelegatedAdminRequests(
            form([
                selection(role("PLAIN")),
                selection(role("D", true), [district("DCC")]),
            ])
        );

        expect(requests.map((r) => r.role_name)).toEqual(["PLAIN", "D"]);
    });

    it("carries every scope on one request rather than splitting it", () => {
        // The backend expands the cross-product, exactly as the grant path
        // does. Splitting here would derive different role names from the same
        // selection, and a delegation whose name does not match what a grant
        // assigns authorises nothing.
        const requests = toDelegatedAdminRequests(
            form([
                selection(
                    role("BOTH", true, true),
                    [district("DCC"), district("DKA")],
                    [client("00001012")]
                ),
            ])
        );

        expect(requests).toHaveLength(1);
        expect(requests[0].scopes).toEqual([
            { type: "DISTRICT", values: ["DCC", "DKA"] },
            { type: "FOREST_CLIENT", values: ["00001012"] },
        ]);
    });

    it("sends no scope for a role that takes none", () => {
        const requests = toDelegatedAdminRequests(form([selection(role("PLAIN"))]));

        expect(requests[0].scopes).toEqual([]);
    });

    it("returns nothing when no user has been chosen", () => {
        expect(
            toDelegatedAdminRequests({
                domain: UserType.Idir,
                users: [],
                roles: [selection(role("PLAIN"))],
            })
        ).toEqual([]);
    });
});

describe("newRoleSelection", () => {
    it("starts with nothing chosen", () => {
        const fresh = newRoleSelection(role("A", true, true));

        expect(fresh.districts).toEqual([]);
        expect(fresh.forestClients).toEqual([]);
    });
});

describe("roleLabel", () => {
    it("falls back to the code when the role has no name", () => {
        expect(roleLabel(role("CHR_FREP_EDITOR"))).toBe("CHR_FREP_EDITOR");
    });
});
