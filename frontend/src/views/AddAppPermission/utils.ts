import {
    MAX_SCOPE_COMBINATIONS,
    newRoleScopeSelection,
    requiresScope,
    roleLabel,
    scopeCombinationCount,
    selectionsOverTheLimit,
    toScopeSelections,
    type RoleScopeSelection,
} from "@/utils/ScopeUtils";
import type { SelectedUser } from "@/types/SelectUserType";
import {
    UserType,
    type CssRoleOptionDto,
    type CssUserRoleAssignmentRequest,
    type CssUserRoleAssignmentResult,
    type FamDistrictDto,
    type FamForestClientDto,
} from "fam-api/model";
import { array, mixed, object, string } from "yup";

// Query-cache keys the grant screen leaves its outcome under, for Manage
// permissions to pick up after the redirect and then clear.
export const AddAppUserPermissionSuccessQuerykey = "app-user-mutation-success";
export const AddAppUserPermissionErrorQuerykey = "app-user-mutation-error";

/**
 * What happened for one user in a grant.
 *
 * CSS assigns to one user at a time, so a multi-user grant is several calls and
 * any of them can fail on its own. Keeping the user alongside their results is
 * what lets the notification say who was granted what - flattening the results
 * together loses exactly that.
 */
/**
 * What happened for one user and one role.
 *
 * A pair rather than a user, because a grant now names several roles and CSS
 * assigns one role to one user per call - so they do not share a fate.
 * Reporting per user would have to pick one of several results to show.
 */
export type UserGrantOutcome = {
    user: SelectedUser;
    role: RoleOption;
    results: CssUserRoleAssignmentResult[];
    /** Set when the call for this pair failed outright, so it has no results. */
    error?: string;
};

/**
 * The reason a grant failed, as the backend gave it.
 *
 * FAM's errors carry a `description` naming the actual problem - a target at
 * another organisation, a role that does not exist - which is worth far more
 * than "Request failed with status code 403".
 */
export const describeGrantError = (error: unknown): string => {
    const response = (error as { response?: { data?: { description?: string } } })
        ?.response;
    return (
        response?.data?.description ??
        (error as Error)?.message ??
        "the grant could not be completed"
    );
};

export type AppPermissionGrantSummary = {
    applicationName: string;
    /** One per user/role pair attempted. Each carries the role it was for. */
    outcomes: UserGrantOutcome[];
};

export const MAX_USERS_GRANTING_ALLOWED = 50;

/**
 * A selectable role in the permission form.
 *
 * Scope comes from flags rather than a type code: CSS has no abstract/concrete
 * concept, and a role is district or client scoped by virtue of the marker roles
 * in its composite chain.
 */
export type RoleOption = CssRoleOptionDto;

export type AppPermissionFormType = {
    domain: UserType;
    users: SelectedUser[];
    /** One entry per chosen role, each carrying its own scope. */
    roles: RoleScopeSelection[];
};

/** Re-exported so the grant screen has one import for its form vocabulary. */
export {
    MAX_SCOPE_COMBINATIONS,
    newRoleScopeSelection,
    requiresScope,
    roleLabel,
    scopeCombinationCount,
    selectionsOverTheLimit,
};

/** Every permission the form will create, across all its roles and users. */
export const totalPermissions = (form: AppPermissionFormType): number =>
    form.users.length *
    form.roles.reduce(
        (total, selection) => total + scopeCombinationCount(selection),
        0
    );

export type AppPermissionQueryErrorType = {
    error: Error;
    formData: AppPermissionFormType;
};

export const getDefaultFormData = (
    domain: UserType
): AppPermissionFormType => ({
    domain,
    users: [],
    roles: [],
});

export const validateAppPermissionForm = () =>
    object({
        domain: string().required(),
        users: array()
            .of(mixed<SelectedUser>().required())
            .min(1, "At least one user is required")
            .max(
                MAX_USERS_GRANTING_ALLOWED,
                `At most ${MAX_USERS_GRANTING_ALLOWED} users can be granted at once`
            ),
        roles: array()
            .of(
                object({
                    role: mixed<RoleOption>().required(),
                    // Required only for the roles scoped that way, so choosing
                    // an unscoped role never blocks the form.
                    districts: array()
                        .of(mixed<FamDistrictDto>().required())
                        .when("role", {
                            is: (role: RoleOption) =>
                                Boolean(role?.role_type_district),
                            then: (schema) =>
                                schema.min(
                                    1,
                                    "Choose at least one district for this role"
                                ),
                            otherwise: (schema) => schema.default([]),
                        }),
                    forestClients: array()
                        .of(mixed<FamForestClientDto>().required())
                        .when("role", {
                            is: (role: RoleOption) =>
                                Boolean(role?.role_type_client),
                            then: (schema) =>
                                schema.min(
                                    1,
                                    "Choose at least one organization for this role"
                                ),
                            otherwise: (schema) => schema.default([]),
                        }),
                })
            )
            .min(1, "Please select at least one role"),
    });

/**
 * One CSS assignment request per user, per role.
 *
 * CSS grants a single role to a single user at a time, so a grant of three roles
 * to two people is six calls. The role travels with the request so the outcome
 * can be attributed to the pair it came from - the calls do not share a fate,
 * and reporting per user would have to pick one of several results to show.
 *
 * Scope values travel as bare strings; the backend turns each combination into a
 * scope-specific role, because CSS roles carry no attributes and the name is
 * what reaches the token.
 */
export type PlannedGrant = {
    user: SelectedUser;
    role: RoleOption;
    request: CssUserRoleAssignmentRequest;
};

export const planGrants = (
    formData: AppPermissionFormType
): PlannedGrant[] => {
    const planned: PlannedGrant[] = [];

    // Users outer, roles inner: a person being granted three roles sees them
    // attempted together, so a failure part-way through leaves one person half
    // done rather than every person half done.
    for (const user of formData.users) {
        for (const selection of formData.roles) {
            planned.push({
                user,
                role: selection.role,
                request: {
                    user_guid: user.guid ?? "",
                    user_type: formData.domain,
                    role_name: selection.role.name,
                    // The address travels with the request because the picker
                    // already has it - the directory search returns it alongside
                    // the GUID. It only addresses the notification; who gets
                    // granted what comes from the GUID.
                    target_user_email: user.email ?? undefined,
                    scopes: toScopeSelections(
                        selection.role,
                        selection.districts,
                        selection.forestClients
                    ),
                },
            });
        }
    }
    return planned;
};
