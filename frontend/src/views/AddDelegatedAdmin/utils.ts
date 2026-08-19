import type { CssDelegatedAdminRequest } from "fam-api/model";
import type { AppPermissionFormType } from "@/views/AddAppPermission/utils";

/**
 * Appointing a delegated administrator collects the same fields as granting a
 * role - a user, a role, and the scope values that role needs - so the form
 * shape is shared with the grant screen rather than duplicated.
 *
 * What differs is what the answers mean. On the grant screen the districts are
 * the ones the person is *getting*; here they are the ones the person may
 * *hand out*. That difference is in the wording, not the data.
 */
export type DelegatedAdminFormType = AppPermissionFormType;

/** Delegating a role that is not scoped, or one scope value at a time. */
export const toDelegatedAdminRequests = (
    form: DelegatedAdminFormType
): CssDelegatedAdminRequest[] => {
    const user = form.users[0];
    if (!user || !form.role) {
        return [];
    }

    // Mirrors generateCssRequests on the grant screen deliberately: a delegation
    // has to name the role a grant will actually assign, so if the two derived
    // scope values differently a delegation would authorise nothing.
    const districtScoped = Boolean(form.role.role_type_district);
    const clientScoped = Boolean(form.role.role_type_client);

    const scopeType = districtScoped
        ? "DISTRICT"
        : clientScoped
          ? "FOREST_CLIENT"
          : undefined;

    const scopeValues = districtScoped
        ? form.districts.map((district) => district.org_unit_code)
        : clientScoped
          ? form.forestClients.map((client) => client.forest_client_number)
          : [];

    // One request carries every scope value: the backend creates one delegation
    // per value, matching how a scoped grant creates one role per value.
    return [
        {
            user_guid: user.guid ?? "",
            user_type: form.domain,
            role_name: form.role.name,
            scope_type: scopeType,
            scope_values: scopeValues,
        },
    ];
};

/**
 * Why an appointment was refused, preferring the backend's own message.
 *
 * It names the actual problem - appointing yourself, a BCeID administrator
 * reaching outside their organisation - which a generic line would hide.
 */
export const describeAppointmentError = (error: unknown): string => {
    const response = (error as any)?.response?.data;
    return (
        response?.description ??
        (error as any)?.message ??
        "The delegated administrator could not be appointed."
    );
};
