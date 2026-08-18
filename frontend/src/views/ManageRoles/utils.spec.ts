import { describe, expect, it } from "vitest";
import {
    applyScopeChoice,
    getDefaultFormData,
    toCreateRequest,
    validateManageRolesForm,
    type ManageRolesFormType,
} from "./utils";

const form = (overrides: Partial<ManageRolesFormType> = {}): ManageRolesFormType => ({
    ...getDefaultFormData(),
    roleCode: "FREP_ADMINISTRATOR",
    roleName: "FREP Administrator",
    description: "",
    ...overrides,
});

const validate = (values: ManageRolesFormType) =>
    validateManageRolesForm().validate(values, { abortEarly: false });

describe("role code validation", () => {
    it("accepts a code that can be a role name, a scope prefix and a sidecar key", async () => {
        await expect(validate(form())).resolves.toBeTruthy();
    });

    it.each([
        ["a space", "FREP ADMIN"],
        ["a hyphen, which is the scope value separator", "FREP-ADMIN"],
        ["a colon, which is the sidecar separator", "FREP:ADMIN"],
        ["a leading digit", "1_FREP"],
    ])("rejects %s", async (_reason, roleCode) => {
        // The code becomes the role name and the left-hand side of two naming
        // conventions, so a delimiter in it would make either ambiguous.
        await expect(validate(form({ roleCode }))).rejects.toBeTruthy();
    });

    it("accepts a lower case code, which is upper cased on the way out", async () => {
        await expect(
            validate(form({ roleCode: "frep_administrator" }))
        ).resolves.toBeTruthy();
        expect(toCreateRequest(form({ roleCode: " frep_administrator " })).role_code).toBe(
            "FREP_ADMINISTRATOR"
        );
    });

    it("requires a role name", async () => {
        await expect(validate(form({ roleName: "  " }))).rejects.toBeTruthy();
    });

    it("rejects a role name too long to fit in a sidecar role name", async () => {
        await expect(
            validate(form({ roleName: "x".repeat(151) }))
        ).rejects.toBeTruthy();
    });

    it("accepts an absent description - it is optional", async () => {
        // A role whose name says enough needs no sentence.
        await expect(validate(form({ description: "" }))).resolves.toBeTruthy();
    });

    it("rejects a description too long to fit in its own sidecar", async () => {
        await expect(
            validate(form({ description: "x".repeat(201) }))
        ).rejects.toBeTruthy();
    });
});

describe("scope selection", () => {
    it("clears the other scope when one is ticked", () => {
        // A grant carries a single scope type. Marked both, the role would
        // silently behave as district scoped and its client side would be
        // unreachable, so the pair is never allowed to exist.
        const districtOnly = applyScopeChoice(
            form({ requiresForestClient: true }),
            "requiresDistrict",
            true
        );

        expect(districtOnly.requiresDistrict).toBe(true);
        expect(districtOnly.requiresForestClient).toBe(false);
    });

    it("leaves the other scope alone when one is unticked", () => {
        const cleared = applyScopeChoice(
            form({ requiresDistrict: true }),
            "requiresDistrict",
            false
        );

        expect(cleared.requiresDistrict).toBe(false);
        expect(cleared.requiresForestClient).toBe(false);
    });

    it("sends an unscoped role with neither flag", () => {
        const request = toCreateRequest(form());

        expect(request.requires_district).toBe(false);
        expect(request.requires_forest_client).toBe(false);
    });

    it("sends the district flag on a district-scoped role", () => {
        expect(
            toCreateRequest(form({ requiresDistrict: true })).requires_district
        ).toBe(true);
    });
});
