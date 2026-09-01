// Backend API error `code` values (see response `detail.code`).

export const PERMISSION_REQUIRED_FOR_OPERATION = "permission_required_for_operation";

export const SELF_GRANT_PROHIBITED_ERROR_CODE = "self_grant_prohibited";

/**
 * A Business BCeID caller reaching a user at another business.
 *
 * Its own code rather than the general permission refusal, because the search
 * says something specific about it and says it against the field - see
 * UserSearch. Raised by AuthorizationService.enforceSameOrganization.
 */
export const DIFFERENT_ORG_GRANT_PROHIBITED = "different_org_grant_prohibited";
