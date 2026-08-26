import { describe, expect, it } from "vitest";
import {
    MAX_DESCRIPTION_LENGTH,
    MAX_ROLE_NAME_LENGTH,
    getDefaultFormData,
    type ManageRolesFormType,
} from "./roleUtils";
import { hasRoleFormErrors, validateRoleForm } from "./validation";

/**
 * What a role definition has to satisfy before it is worth sending.
 *
 * The code rules are the interesting ones: the code becomes the CSS role name,
 * which is what reaches the access token, and it is the left-hand side of both
 * the scope suffix and the sidecars carrying the name and description - so a
 * character that collides with either delimiter breaks the parsing everything
 * else depends on.
 */

const form = (over: Partial<ManageRolesFormType> = {}): ManageRolesFormType => ({
    ...getDefaultFormData(),
    roleCode: "FREP_EDITOR",
    roleName: "Editor",
    ...over,
});

describe("validateRoleForm", () => {
    it("accepts a well-formed role", () => {
        expect(hasRoleFormErrors(validateRoleForm(form()))).toBe(false);
    });

    it("requires a code", () => {
        expect(validateRoleForm(form({ roleCode: "" })).roleCode).toBe(
            "A role code is required"
        );
    });

    it("requires a name", () => {
        expect(validateRoleForm(form({ roleName: "  " })).roleName).toBe(
            "A role name is required"
        );
    });

    it("accepts a lower case code rather than rejecting it on a technicality", () => {
        // The request upper cases it, so the validation does too.
        expect(
            validateRoleForm(form({ roleCode: "frep_editor" })).roleCode
        ).toBeUndefined();
    });

    it("refuses a hyphen in the code", () => {
        // The scope suffix is `_DISTRICT-DCC`, so a hyphen in the code itself
        // would make the scope unparseable from the role name.
        expect(validateRoleForm(form({ roleCode: "FREP-EDITOR" })).roleCode)
            .toBeDefined();
    });

    it("refuses a colon in the code", () => {
        // `FAM:LABEL:` and `FAM:DESC:` are the sidecar prefixes.
        expect(
            validateRoleForm(form({ roleCode: "FREP:EDITOR" })).roleCode
        ).toBeDefined();
    });

    it("refuses a code that does not start with a letter", () => {
        expect(validateRoleForm(form({ roleCode: "1FREP" })).roleCode).toBeDefined();
    });

    it("refuses a single-character code", () => {
        // The pattern needs at least two: a letter and one more.
        expect(validateRoleForm(form({ roleCode: "F" })).roleCode).toBeDefined();
    });

    it("refuses a code longer than the name budget allows", () => {
        // 59 characters is the ceiling; the sidecars need the rest of the 255.
        const tooLong = "F" + "A".repeat(59);

        expect(validateRoleForm(form({ roleCode: tooLong })).roleCode).toBeDefined();
    });

    it("treats the description as optional", () => {
        expect(
            validateRoleForm(form({ description: "" })).description
        ).toBeUndefined();
    });

    it("bounds the description at what fits in a Keycloak role name", () => {
        expect(
            validateRoleForm(form({ description: "x".repeat(MAX_DESCRIPTION_LENGTH + 1) }))
                .description
        ).toContain(String(MAX_DESCRIPTION_LENGTH));
    });

    it("bounds the name too", () => {
        expect(
            validateRoleForm(form({ roleName: "x".repeat(MAX_ROLE_NAME_LENGTH + 1) }))
                .roleName
        ).toContain(String(MAX_ROLE_NAME_LENGTH));
    });

    it("measures the description after trimming", () => {
        // Trailing spaces are stripped before it is sent, so they must not push
        // an acceptable description over the limit.
        const atLimit = "x".repeat(MAX_DESCRIPTION_LENGTH) + "   ";

        expect(
            validateRoleForm(form({ description: atLimit })).description
        ).toBeUndefined();
    });

    it("reports every problem at once", () => {
        // The form shows them all beside their fields, so stopping at the first
        // would make somebody submit three times to find three faults.
        const errors = validateRoleForm(
            form({ roleCode: "", roleName: "", description: "x".repeat(300) })
        );

        expect(Object.keys(errors).sort()).toEqual([
            "description",
            "roleCode",
            "roleName",
        ]);
    });
});
