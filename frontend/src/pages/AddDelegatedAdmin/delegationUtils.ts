import type { SelectedUser } from "@/types/SelectUserType";
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
import type { RoleOption } from "@/pages/AddAppPermission/grantUtils";
import {
    UserType,
    type CssDelegatedAdminRequest,
    type FamDistrictDto,
    type FamForestClientDto,
} from "fam-api/model";
import { array, mixed, object, string } from "yup";
import { describeApiError } from "@/utils/ApiUtils";

/**
 * One role this person may hand out, with what they may hand it out for.
 *
 * The shape is shared with the grant screen - see {@link RoleScopeSelection}.
 * What differs is what the answers mean: there the districts are what somebody
 * is being given, here they are what they may hand out.
 */
export type DelegatedRoleSelection = RoleScopeSelection;

export type DelegatedAdminFormType = {
    domain: UserType;
    users: SelectedUser[];
    roles: DelegatedRoleSelection[];
};

/** Named for this screen; the arithmetic is the shared one. */
export const delegationCount = scopeCombinationCount;
export const MAX_DELEGATIONS_PER_ROLE = MAX_SCOPE_COMBINATIONS;
export const newRoleSelection = newRoleScopeSelection;
export { requiresScope, roleLabel };

/** Every delegation the form will create, across all its roles. */
export const totalDelegations = (form: DelegatedAdminFormType): number =>
    form.roles.reduce((total, selection) => total + delegationCount(selection), 0);

/** Roles whose scope selection has already outgrown what the backend accepts. */
export const rolesOverTheLimit = (
    form: DelegatedAdminFormType
): DelegatedRoleSelection[] => selectionsOverTheLimit(form.roles);

/**
 * One request per role.
 *
 * Not one per scope combination: a request carries every scope it needs and the
 * backend expands the cross-product, exactly as the grant path does. Splitting
 * here would mean the two derived different role names, and a delegation whose
 * name does not match what a grant assigns authorises nothing.
 */
export const toDelegatedAdminRequests = (
    form: DelegatedAdminFormType
): CssDelegatedAdminRequest[] => {
    const user = form.users[0];
    if (!user) {
        return [];
    }

    return form.roles.map((selection) => ({
        user_guid: user.guid ?? "",
        user_type: form.domain,
        role_name: selection.role.name,
        // The same builder the grant screen uses, not a copy of its logic.
        scopes: toScopeSelections(
            selection.role,
            selection.districts,
            selection.forestClients,
            selection.regions
        ),
    }));
};

export const getDefaultFormData = (
    domain: UserType
): DelegatedAdminFormType => ({
    domain,
    users: [],
    roles: [],
});

export const validateDelegatedAdminForm = () =>
    object({
        domain: string().required(),
        users: array()
            .of(mixed<SelectedUser>().required())
            .min(1, "Select the user who will be a delegated admin"),
        roles: array()
            .of(
                object({
                    role: mixed<RoleOption>().required(),
                    // Required only for the roles that are scoped that way, so
                    // ticking an unscoped role never blocks the form.
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
            .min(1, "Select at least one role they may grant"),
    });

/**
 * Why an appointment was refused, preferring the backend's own message.
 *
 * It names the actual problem - appointing yourself, a BCeID administrator
 * reaching outside their organisation - which a generic line would hide.
 */
export const describeAppointmentError = (error: unknown): string =>
    describeApiError(error, "The delegated administrator could not be appointed.");
