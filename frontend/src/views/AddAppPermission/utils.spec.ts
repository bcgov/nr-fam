import { UserType } from "fam-api/model";
import { describe, expect, it } from "vitest";
import { generateCssRequests, type AppPermissionFormType } from "./utils";

/**
 * The CSS request payload.
 *
 * The email in particular is worth a test: if it silently stops being sent the
 * grant still succeeds, so nothing fails visibly - the granted user just never
 * hears about it.
 */
describe("generateCssRequests", () => {
    const role = (overrides = {}) => ({
        name: "CHR_FREP_EDITOR",
        composite: false,
        composites: [],
        role_type_district: false,
        role_type_client: false,
        ...overrides,
    });

    const form = (overrides: Partial<AppPermissionFormType> = {}) =>
        ({
            domain: UserType.Idir,
            users: [{ userId: "JSMITH", guid: "ABC123", email: "jane@gov.bc.ca" }],
            forestClients: [],
            districts: [],
            role: role(),
            ...overrides,
        }) as AppPermissionFormType;

    it("carries the selected user's email", () => {
        expect(generateCssRequests(form())[0].target_user_email).toBe(
            "jane@gov.bc.ca"
        );
    });

    it("omits the email when the directory had none", () => {
        // Sent as absent rather than empty, so the backend's @Email constraint
        // has nothing to reject.
        const requests = generateCssRequests(
            form({ users: [{ userId: "JSMITH", guid: "ABC123", email: null }] })
        );

        expect(requests[0].target_user_email).toBeUndefined();
    });

    it("makes one request per selected user, each with its own email", () => {
        // CSS grants to one user at a time; a multi-user selection must not
        // collapse to a single address.
        const requests = generateCssRequests(
            form({
                users: [
                    { userId: "JSMITH", guid: "A", email: "jane@gov.bc.ca" },
                    { userId: "BJONES", guid: "B", email: "bo@gov.bc.ca" },
                ],
            })
        );

        expect(requests.map((r) => r.target_user_email)).toEqual([
            "jane@gov.bc.ca",
            "bo@gov.bc.ca",
        ]);
        expect(requests.map((r) => r.user_guid)).toEqual(["A", "B"]);
    });

    it("sends district codes as scope values", () => {
        const requests = generateCssRequests(
            form({
                role: role({ role_type_district: true }),
                districts: [
                    {
                        org_unit_code: "DCC",
                        org_unit_name: "Cariboo-Chilcotin",
                        expired: false,
                    },
                    { org_unit_code: "DQU", org_unit_name: "Quesnel", expired: false },
                ],
            })
        );

        expect(requests[0].scope_type).toBe("DISTRICT");
        expect(requests[0].scope_values).toEqual(["DCC", "DQU"]);
    });

    it("returns nothing when no role is selected", () => {
        expect(generateCssRequests(form({ role: null }))).toEqual([]);
    });
});
