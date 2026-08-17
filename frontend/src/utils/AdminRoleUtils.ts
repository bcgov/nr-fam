import { authState } from "@/providers/authState";

/**
 * FAM's super administrator role.
 *
 * Kept in step with `FamAdminRole.FAM_ADMIN` on the backend. The other two tiers
 * (`APP_ADMIN_<integration>_<env>`, `DELEGATED_ADMIN_<integration>_<env>`) carry
 * an application in the name, so they are matched per application rather than by
 * a constant.
 */
export const FAM_ADMIN_ROLE = "FAM_ADMIN";

/**
 * Whether the signed-in user holds FAM_ADMIN.
 *
 * Decides what the UI offers, nothing more. Every endpoint re-checks the caller's
 * roles server-side, so making this return true buys access to a screen whose
 * every call still answers 403.
 */
export const isFamAdmin = (): boolean =>
    authState.value.accessRoles.some(
        (role) => role.toUpperCase() === FAM_ADMIN_ROLE
    );
