import type { RoleScopeSelection } from "@/utils/ScopeUtils";
import type { SelectedUser } from "@/types/SelectUserType";
import { MAX_USERS_GRANTING_ALLOWED } from "@/pages/AddAppPermission/grantUtils";

/**
 * What is missing from a grant form, if anything.
 *
 * Plain functions rather than the yup schema the Vue screen fed to vee-validate.
 * The schema described the same three rules, but only vee-validate could run it -
 * so the rules could not be tested without mounting a form, and the per-role
 * messages arrived keyed by a path string the component had to parse back apart.
 */

export type GrantFormErrors = {
    users?: string;
    roles?: string;
    /**
     * Keyed by role name, because that is what the card renders under. Index
     * would break the moment a role is removed from the middle of the list.
     */
    perRole: Record<
        string,
        { districts?: string; regions?: string; forestClients?: string }
    >;
};

export const NO_ERRORS: GrantFormErrors = { perRole: {} };

export const validateGrantForm = (form: {
    users: SelectedUser[];
    roles: RoleScopeSelection[];
}): GrantFormErrors => {
    const errors: GrantFormErrors = { perRole: {} };

    if (form.users.length === 0) {
        errors.users = "At least one user is required";
    } else if (form.users.length > MAX_USERS_GRANTING_ALLOWED) {
        errors.users = `At most ${MAX_USERS_GRANTING_ALLOWED} users can be granted at once`;
    }

    if (form.roles.length === 0) {
        errors.roles = "Please select at least one role";
    }

    for (const selection of form.roles) {
        const roleErrors: {
            districts?: string;
            regions?: string;
            forestClients?: string;
        } = {};
        // Checked only for the roles scoped that way, so choosing an unscoped
        // role never blocks the form.
        if (
            selection.role.role_type_district &&
            selection.districts.length === 0
        ) {
            roleErrors.districts = "Choose at least one district for this role";
        }
        if (selection.role.role_type_region && selection.regions.length === 0) {
            roleErrors.regions = "Choose at least one region for this role";
        }
        if (
            selection.role.role_type_client &&
            selection.forestClients.length === 0
        ) {
            roleErrors.forestClients =
                "Choose at least one organization for this role";
        }
        if (roleErrors.districts || roleErrors.regions || roleErrors.forestClients) {
            errors.perRole[selection.role.name] = roleErrors;
        }
    }

    return errors;
};

export const hasErrors = (errors: GrantFormErrors): boolean =>
    Boolean(errors.users) ||
    Boolean(errors.roles) ||
    Object.keys(errors.perRole).length > 0;
