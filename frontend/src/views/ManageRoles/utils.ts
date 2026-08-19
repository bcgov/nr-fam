import type { CssRoleCreateRequest } from "fam-api/model";
import { boolean, object, string } from "yup";

/**
 * A role code, mirroring `CssRoleNaming.isValidRoleCode` on the backend.
 *
 * Narrower than what CSS itself accepts. The code becomes the role name, which
 * is what reaches the access token and what applications authorise on, and it is
 * the left-hand side of both the scope suffix (`_DISTRICT-DCC`) and the sidecars
 * that hold the name and description - so it must not contain either delimiter.
 *
 * Checked here only to say so before the round trip; the backend re-checks.
 */
export const ROLE_CODE_PATTERN = /^[A-Z][A-Z0-9_]{1,58}$/;

/** Bounds the FAM:LABEL sidecar, which must fit inside one Keycloak role name. */
export const MAX_ROLE_NAME_LENGTH = 150;

/** Bounds the FAM:DESC sidecar. Its own role name, so it has its own budget. */
export const MAX_DESCRIPTION_LENGTH = 200;

export type ManageRolesFormType = {
    /** FSPTS_VIEW_ALL - what reaches the token. */
    roleCode: string;
    /** View All - what pickers and pills show. */
    roleName: string;
    /** The sentence explaining the role. Optional. */
    description: string;
    requiresDistrict: boolean;
    requiresForestClient: boolean;
};

export const getDefaultFormData = (): ManageRolesFormType => ({
    roleCode: "",
    roleName: "",
    description: "",
    requiresDistrict: false,
    requiresForestClient: false,
});

export const validateManageRolesForm = () =>
    object().shape({
        roleCode: string()
            .required("A role code is required")
            // Upper cased on the way in, so a lower case entry is accepted
            // rather than rejected on a technicality.
            .transform((value) => (value ? value.trim().toUpperCase() : value))
            .matches(
                ROLE_CODE_PATTERN,
                "Use letters, digits and underscores only, starting with a letter, e.g. FREP_ADMINISTRATOR"
            ),
        roleName: string()
            .required("A role name is required")
            .trim()
            .max(
                MAX_ROLE_NAME_LENGTH,
                `Keep the name under ${MAX_ROLE_NAME_LENGTH} characters`
            ),
        // Optional: a role whose name says enough needs no sentence.
        description: string()
            .trim()
            .max(
                MAX_DESCRIPTION_LENGTH,
                `Keep the description under ${MAX_DESCRIPTION_LENGTH} characters`
            ),
        requiresDistrict: boolean(),
        requiresForestClient: boolean(),
    });

/**
 * The two scope checkboxes are mutually exclusive.
 *
 * A grant carries a single scope type and the picker offers one kind of scope,
 * so a role marked both would silently behave as district scoped and its forest
 * client side would be unreachable. Ticking one clears the other rather than
 * letting the pair be submitted and refused.
 */
export const applyScopeChoice = (
    form: ManageRolesFormType,
    field: "requiresDistrict" | "requiresForestClient",
    checked: boolean
): ManageRolesFormType => ({
    ...form,
    requiresDistrict:
        field === "requiresDistrict" ? checked : checked ? false : form.requiresDistrict,
    requiresForestClient:
        field === "requiresForestClient"
            ? checked
            : checked
              ? false
              : form.requiresForestClient,
});

/** The create request, normalised the same way the backend will normalise it. */
export const toCreateRequest = (
    form: ManageRolesFormType
): CssRoleCreateRequest => ({
    role_code: form.roleCode.trim().toUpperCase(),
    role_name: form.roleName.trim(),
    description: form.description.trim(),
    requires_district: form.requiresDistrict,
    requires_forest_client: form.requiresForestClient,
});

/**
 * How a role's scope reads once created.
 *
 * Empty when neither box is ticked: the unscoped case is the default and the
 * two checkboxes already say what they do, so a line explaining that nothing
 * further is required only adds noise. The caller hides the text when it is
 * empty rather than rendering a blank line.
 */
export const describeScope = (form: ManageRolesFormType): string => {
    if (form.requiresDistrict) {
        return "Districts must be chosen when this role is granted";
    }
    if (form.requiresForestClient) {
        return "Forest clients must be chosen when this role is granted";
    }
    return "";
};
