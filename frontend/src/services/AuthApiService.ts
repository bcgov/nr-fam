/**
 * The caller's FAM identity and access roles.
 *
 * `POST /auth/login` used to be mandatory - it provisioned the caller's
 * `fam_user` row, and every other endpoint answered `403 requester_not_exists`
 * until it had run. FAM keeps no user table now and the gate is gone, so a valid
 * token is enough on the very first request.
 *
 * The call is kept because the frontend wants identity and roles at sign-in
 * anyway; it is simply no longer a precondition for anything.
 */

import axios from "axios";
import type { SelfApplicationRoleDto, SelfPermissionDto } from "fam-api";
import { EnvironmentSettings } from "@/services/EnvironmentSettings";

const environmentSettings = new EnvironmentSettings();

/**
 * The caller's FAM identity and effective access roles.
 *
 * `accessRoles` was the `cognito:groups` token claim; it is now the token's
 * `client_roles`, so a revocation in CSS takes effect at the next token refresh.
 */
export interface FamSelf {
    user_name: string;
    user_type_code: string | null;
    first_name: string | null;
    last_name: string | null;
    email: string | null;
    access_roles: string[];
    is_delegated_admin: boolean;
    requires_accept_tc: boolean;
}

const authBaseUrl = (): string => `${environmentSettings.getApiBaseUrl()}/auth`;

/**
 * Provision the signed-in user and return their identity and roles.
 * Idempotent — safe on every sign-in and token refresh.
 */
export const bootstrapLogin = async (): Promise<FamSelf> => {
    const { data } = await axios.post<FamSelf>(`${authBaseUrl()}/login`);
    return data;
};

/** Current identity and roles, without provisioning. Used when restoring a session. */
export const fetchSelf = async (): Promise<FamSelf> => {
    const { data } = await axios.get<FamSelf>(`${authBaseUrl()}/self`);
    return data;
};

/**
 * The caller's own administrative permissions, with applications named.
 *
 * The same roles `fetchSelf` returns, decoded: `APP_ADMIN_22264_DEV` becomes the
 * application it refers to and the tier it grants. The decoding is the backend's
 * because the role-name grammar is a contract that lives there, and because only
 * it can resolve an integration id to a project name.
 */
export const fetchSelfPermissions = async (): Promise<SelfPermissionDto[]> => {
    const { data } = await axios.get<SelfPermissionDto[]>(
        `${authBaseUrl()}/self/permissions`
    );
    return data;
};

/**
 * Every application role the caller holds, across the integrations FAM can see.
 *
 * Materially slower than `fetchSelfPermissions`: that answers from the token,
 * this asks CSS once per integration and environment. Kept separate so the
 * screen can show the administrative half immediately.
 */
export const fetchSelfApplicationRoles = async (): Promise<
    SelfApplicationRoleDto[]
> => {
    const { data } = await axios.get<SelfApplicationRoleDto[]>(
        `${authBaseUrl()}/self/application-roles`,
        // The fan-out can outlast the default 10s, and a timeout here loses the
        // whole table rather than degrading it.
        { timeout: 60_000 }
    );
    return data;
};
