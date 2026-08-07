/**
 * The FAM sign-in bootstrap.
 *
 * AWS Cognito ran a pre-token-generation Lambda on every login that created the
 * user's `fam_user` row and injected their FAM roles into the token. A BC Gov SSO
 * realm cannot run application code at token time, so the backend does both
 * itself and exposes them here.
 *
 * `POST /auth/login` MUST be called once after a successful Keycloak sign-in and
 * before any other API call. Until it is, a first-time user holds a valid token
 * but has no FAM identity, and every other endpoint answers
 * `403 requester_not_exists`.
 */

import axios from "axios";
import { EnvironmentSettings } from "@/services/EnvironmentSettings";

const environmentSettings = new EnvironmentSettings();

/**
 * The caller's FAM identity and effective access roles.
 *
 * `accessRoles` was the `cognito:groups` token claim. It is now resolved from the
 * database per request, so a revocation takes effect immediately rather than at
 * the next token refresh.
 */
export interface FamSelf {
    user_id: number;
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
