import {
    MAX_DESCRIPTION_LENGTH,
    MAX_ROLE_NAME_LENGTH,
    ROLE_CODE_PATTERN,
    type ManageRolesFormType,
} from "@/pages/ManageRoles/roleUtils";

/**
 * What is wrong with a role definition, if anything.
 *
 * Plain functions rather than the yup schema fed through `validate({ abortEarly:
 * false })` and unpacked from `error.inner`. Same three rules, but they can be
 * tested without constructing a rejected promise and reading a library's error
 * shape back apart.
 *
 * <b>The duplicate-code check is deliberately absent.</b> That is the backend's:
 * only it can see what already exists, and for the all-environments case it has
 * to look in every environment before writing to any.
 */
export type RoleFormErrors = Partial<
    Record<"roleCode" | "roleName" | "description", string>
>;

export const validateRoleForm = (form: ManageRolesFormType): RoleFormErrors => {
    const errors: RoleFormErrors = {};

    // Upper cased before matching, so a lower case entry is accepted rather than
    // rejected on a technicality - the request upper cases it too.
    const code = form.roleCode.trim().toUpperCase();
    if (!code) {
        errors.roleCode = "A role code is required";
    } else if (!ROLE_CODE_PATTERN.test(code)) {
        errors.roleCode =
            "Use letters, digits and underscores only, starting with a letter, e.g. FREP_ADMINISTRATOR";
    }

    const name = form.roleName.trim();
    if (!name) {
        errors.roleName = "A role name is required";
    } else if (name.length > MAX_ROLE_NAME_LENGTH) {
        errors.roleName = `Keep the name under ${MAX_ROLE_NAME_LENGTH} characters`;
    }

    // Optional: a role whose name says enough needs no sentence.
    if (form.description.trim().length > MAX_DESCRIPTION_LENGTH) {
        errors.description = `Keep the description under ${MAX_DESCRIPTION_LENGTH} characters`;
    }

    return errors;
};

export const hasRoleFormErrors = (errors: RoleFormErrors): boolean =>
    Object.keys(errors).length > 0;
