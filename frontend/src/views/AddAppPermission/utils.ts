import type { TextInputType } from "@/types/InputTypes";
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
export type UserGrantOutcome = {
    user: SelectedUser;
    results: CssUserRoleAssignmentResult[];
    /** Set when the call for this user failed outright, so it has no results. */
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
    roleName: string;
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
    forestClients: FamForestClientDto[];
    districts: FamDistrictDto[];
    role: RoleOption | null;
    forestClientInput: TextInputType & {
        /**
         * Track if a verification of a client number is in progress. Role
         * selection is disabled while verifying, otherwise a client could be
         * added right after switching.
         */
        isVerifying: boolean;
    };
};

export type AppPermissionQueryErrorType = {
    error: Error;
    formData: AppPermissionFormType;
};

const defaultFormData: AppPermissionFormType = {
    domain: UserType.BceidBus,
    users: [],
    forestClients: [],
    districts: [],
    role: null,
    forestClientInput: {
        id: "forest-client-number-input",
        value: "",
        isValid: true,
        errorMsg: "",
        isVerifying: false,
    },
};

export const getDefaultFormData = (domain: UserType): AppPermissionFormType => {
    const copy = structuredClone(defaultFormData);
    return { ...copy, domain };
};

/** True when a forest client must be chosen before the role can be granted. */
export const isClientScopedRoleSelected = (
    formData?: AppPermissionFormType
): boolean => Boolean(formData?.role?.role_type_client);

/** True when one or more districts must be chosen before the role can be granted. */
export const isDistrictScopedRoleSelected = (
    formData?: AppPermissionFormType
): boolean => Boolean(formData?.role?.role_type_district);

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
        role: mixed<RoleOption>().required("Please select a role"),
        forestClients: array()
            .of(mixed<FamForestClientDto>().required())
            .when("role", {
                is: (role: RoleOption | null) => Boolean(role?.role_type_client),
                then: (schema) =>
                    schema.min(1, "At least one organization is required"),
                otherwise: (schema) => schema.default([]).nullable(),
            }),
        districts: array()
            .of(mixed<FamDistrictDto>().required())
            .when("role", {
                is: (role: RoleOption | null) =>
                    Boolean(role?.role_type_district),
                then: (schema) =>
                    schema.min(1, "At least one district is required"),
                otherwise: (schema) => schema.default([]).nullable(),
            }),
    });

/**
 * One CSS assignment request per selected user.
 *
 * CSS grants a role to one user at a time, so a multi-user selection becomes one
 * request each rather than a single batch call. Scope values travel as bare
 * strings; the backend turns each into a scope-specific role, because CSS roles
 * carry no attributes and the name is what reaches the token.
 */
export const generateCssRequests = (
    formData: AppPermissionFormType
): CssUserRoleAssignmentRequest[] => {
    const role = formData.role;
    if (!role) {
        return [];
    }

    const districtScoped = Boolean(role.role_type_district);
    const clientScoped = Boolean(role.role_type_client);

    const scopeType = districtScoped
        ? "DISTRICT"
        : clientScoped
          ? "FOREST_CLIENT"
          : undefined;

    const scopeValues = districtScoped
        ? formData.districts.map((d) => d.org_unit_code)
        : clientScoped
          ? formData.forestClients.map((c) => c.forest_client_number)
          : [];

    // The address travels with the request because the picker already has it -
    // the directory search returns it alongside the GUID. It only addresses the
    // notification; who gets granted what comes from the GUID.
    return formData.users.map((user) => ({
        user_guid: user.guid ?? "",
        user_type: formData.domain,
        role_name: role.name,
        target_user_email: user.email ?? undefined,
        scope_type: scopeType,
        scope_values: scopeValues,
    }));
};
