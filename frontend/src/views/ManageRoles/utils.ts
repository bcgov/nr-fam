import type { CssRoleCreateRequest } from "fam-api/model";
import { boolean, object, string } from "yup";

/**
 * A role code, mirroring `CssRoleNaming.isValidRoleCode` on the backend.
 *
 * Narrower than what CSS itself accepts. The code becomes the role name, which
 * is what reaches the access token and what applications authorise on, and it is
 * the left-hand side of both the scope suffix (`_DISTRICT-DCC`) and the sidecar
 * that holds the description - so it must not contain either delimiter.
 *
 * Checked here only to say so before the round trip; the backend re-checks.
 */
export const ROLE_CODE_PATTERN = /^[A-Z][A-Z0-9_]{1,58}$/;

export const MAX_DESCRIPTION_LENGTH = 150;

export type ManageRolesFormType = {
    roleCode: string;
    description: string;
    requiresDistrict: boolean;
    requiresForestClient: boolean;
};

export const getDefaultFormData = (): ManageRolesFormType => ({
    roleCode: "",
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
        description: string()
            .required("A description is required")
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
    description: form.description.trim(),
    requires_district: form.requiresDistrict,
    requires_forest_client: form.requiresForestClient,
});

/** How a role's scope reads once created. */
export const describeScope = (form: ManageRolesFormType): string => {
    if (form.requiresDistrict) {
        return "Districts must be chosen when this role is granted";
    }
    if (form.requiresForestClient) {
        return "Forest clients must be chosen when this role is granted";
    }
    return "This role is granted on its own, with no further selection";
};
