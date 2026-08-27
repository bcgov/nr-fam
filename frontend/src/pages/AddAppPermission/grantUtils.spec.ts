import { UserType } from "fam-api/model";
import { describe, expect, it } from "vitest";
import {
    newRoleScopeSelection,
    planGrants,
    totalPermissions,
    type AppPermissionFormType,
} from "./grantUtils";

/**
 * The CSS request payload.
 *
 * The email in particular is worth a test: if it silently stops being sent the
 * grant still succeeds, so nothing fails visibly - the granted user just never
 * hears about it.
 */
const role = (overrides = {}) =>
    ({
        name: "CHR_FREP_EDITOR",
        display_name: "Submitter (CHR)",
        composite: false,
        composites: [],
        role_type_district: false,
        role_type_client: false,
        ...overrides,
    }) as any;

const selection = (r: any, districts: any[] = [], forestClients: any[] = []) => ({
    ...newRoleScopeSelection(r),
    districts,
    forestClients,
});

const district = (code: string) => ({ org_unit_code: code }) as any;
const client = (number: string) => ({ forest_client_number: number }) as any;

const form = (overrides: Partial<AppPermissionFormType> = {}) =>
    ({
        domain: UserType.Idir,
        users: [{ userId: "JSMITH", guid: "ABC123", email: "jane@gov.bc.ca" }],
        roles: [selection(role())],
        ...overrides,
    }) as AppPermissionFormType;

describe("planGrants", () => {
    it("carries the selected user's email", () => {
        expect(planGrants(form())[0].request.target_user_email).toBe(
            "jane@gov.bc.ca"
        );
    });

    it("omits the email when the directory had none", () => {
        // Sent as absent rather than empty, so the backend's @Email constraint
        // has nothing to reject.
        const planned = planGrants(
            form({ users: [{ userId: "JSMITH", guid: "ABC123", email: null }] as any })
        );

        expect(planned[0].request.target_user_email).toBeUndefined();
    });

    it("makes one request per selected user, each with its own email", () => {
        // CSS grants to one user at a time; a multi-user selection must not
        // collapse to a single address.
        const planned = planGrants(
            form({
                users: [
                    { userId: "JSMITH", guid: "A", email: "jane@gov.bc.ca" },
                    { userId: "BJONES", guid: "B", email: "bo@gov.bc.ca" },
                ] as any,
            })
        );

        expect(planned.map((p) => p.request.target_user_email)).toEqual([
            "jane@gov.bc.ca",
            "bo@gov.bc.ca",
        ]);
        expect(planned.map((p) => p.request.user_guid)).toEqual(["A", "B"]);
    });

    it("makes a request for every user and role pair", () => {
        // CSS assigns one role to one user per call, so two people and three
        // roles is six calls - not two, and not three.
        const planned = planGrants(
            form({
                users: [
                    { userId: "JSMITH", guid: "A" },
                    { userId: "BJONES", guid: "B" },
                ] as any,
                roles: [
                    selection(role({ name: "R1" })),
                    selection(role({ name: "R2" })),
                    selection(role({ name: "R3" })),
                ],
            })
        );

        expect(planned).toHaveLength(6);
        expect(
            planned.map((p) => `${p.request.user_guid}/${p.request.role_name}`)
        ).toEqual([
            // Users outer, roles inner: one person is finished before the next
            // is started, so a failure part-way leaves one person half done
            // rather than everybody half done.
            "A/R1",
            "A/R2",
            "A/R3",
            "B/R1",
            "B/R2",
            "B/R3",
        ]);
    });

    it("attributes each request to the pair it came from", () => {
        // The outcome is reported per user and role, so a request that could not
        // say which role it was for could not be reported at all.
        const planned = planGrants(
            form({ roles: [selection(role({ name: "R1" }))] })
        );

        expect(planned[0].user.userId).toBe("JSMITH");
        expect(planned[0].role.name).toBe("R1");
    });

    it("gives each role its own scope", () => {
        // The scope belongs to the role. Leaking one role's districts onto
        // another would grant something nobody asked for.
        const planned = planGrants(
            form({
                roles: [
                    selection(role({ name: "R1", role_type_district: true }), [
                        district("DCC"),
                    ]),
                    selection(role({ name: "R2" })),
                ],
            })
        );

        expect(planned[0].request.scopes).toEqual([
            { type: "DISTRICT", values: ["DCC"] },
        ]);
        expect(planned[1].request.scopes).toEqual([]);
    });

    it("sends district codes as scope values", () => {
        const planned = planGrants(
            form({
                roles: [
                    selection(role({ role_type_district: true }), [
                        district("DCC"),
                        district("DQU"),
                    ]),
                ],
            })
        );

        expect(planned[0].request.scopes).toEqual([
            { type: "DISTRICT", values: ["DCC", "DQU"] },
        ]);
    });

    it("sends both dimensions when the role requires both", () => {
        // The backend grants every district/client pair. Sending only one
        // dimension would grant the wrong thing rather than fail, because the
        // missing suffix simply would not appear in the role name.
        const planned = planGrants(
            form({
                roles: [
                    selection(
                        role({ role_type_district: true, role_type_client: true }),
                        [district("DCC")],
                        [client("00001012")]
                    ),
                ],
            })
        );

        expect(planned[0].request.scopes).toEqual([
            { type: "DISTRICT", values: ["DCC"] },
            { type: "FOREST_CLIENT", values: ["00001012"] },
        ]);
    });

    it("ignores values for a dimension the role does not require", () => {
        // Switching a role's shape leaves stale selections behind. Sending them
        // would scope a grant by something the role was never defined with.
        const planned = planGrants(
            form({
                roles: [
                    selection(
                        role({ role_type_district: true }),
                        [district("DCC")],
                        [client("00001012")]
                    ),
                ],
            })
        );

        expect(planned[0].request.scopes).toEqual([
            { type: "DISTRICT", values: ["DCC"] },
        ]);
    });

    it("returns nothing when no role is selected", () => {
        expect(planGrants(form({ roles: [] }))).toEqual([]);
    });

    it("returns nothing when no user is selected", () => {
        expect(planGrants(form({ users: [] }))).toEqual([]);
    });
});

describe("totalPermissions", () => {
    it("multiplies users by the roles' combinations", () => {
        // Two people, one plain role and one covering two districts, is six
        // permissions - and nothing else on the form says so.
        expect(
            totalPermissions(
                form({
                    users: [
                        { userId: "A", guid: "A" },
                        { userId: "B", guid: "B" },
                    ] as any,
                    roles: [
                        selection(role({ name: "R1" })),
                        selection(role({ name: "R2", role_type_district: true }), [
                            district("DCC"),
                            district("DQU"),
                        ]),
                    ],
                })
            )
        ).toBe(6);
    });

    it("is zero before anybody is chosen", () => {
        expect(totalPermissions(form({ users: [] }))).toBe(0);
    });
});

/**
 * The expiry date on its way into the request.
 *
 * <p>CSS has nowhere to keep a date, so the backend turns this into a sidecar
 * role assigned beside the grant. What matters here is only that it arrives -
 * and that "no expiry" arrives as absent rather than as an empty string, which
 * is not a date and which the backend would have to guess at.
 */
describe("planGrants expiry", () => {
    it("carries the chosen date to every role in the grant", () => {
        const planned = planGrants(
            form({
                roles: [selection(role("FREP_EDITOR")), selection(role("FREP_VIEWER"))],
                expiresOn: "2026-09-30",
            })
        );

        expect(planned).toHaveLength(2);
        expect(planned.map((one) => one.request.expires_on)).toEqual([
            "2026-09-30",
            "2026-09-30",
        ]);
    });

    it("omits it entirely for access that does not expire", () => {
        // Absent, not "". The backend reads absent as "never expires"; an empty
        // string is not a date and would have to be guessed at.
        expect(planGrants(form({ expiresOn: "" }))[0].request.expires_on).toBeUndefined();
    });

    it("carries it to every user, not just the first", () => {
        const planned = planGrants(
            form({
                users: [
                    { userId: "JSMITH", guid: "ABC", email: "a@gov.bc.ca" },
                    { userId: "BLEE", guid: "DEF", email: "b@gov.bc.ca" },
                ],
                expiresOn: "2026-09-30",
            })
        );

        expect(planned.map((one) => one.request.expires_on)).toEqual([
            "2026-09-30",
            "2026-09-30",
        ]);
    });
});

