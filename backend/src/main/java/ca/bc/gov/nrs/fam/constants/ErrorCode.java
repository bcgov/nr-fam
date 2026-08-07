package ca.bc.gov.nrs.fam.constants;

/**
 * Error codes returned in the {@code failureCode} field of an error response.
 *
 * <p>Ported verbatim from the {@code ERROR_CODE_*} constants in
 * {@code server/backend/api/app/constants.py}. The frontend matches on these
 * string values (see {@code frontend/src/constants/ApiErrorCodes.ts}), so the
 * wire values must not change.
 */
public final class ErrorCode {

  private ErrorCode() {}

  public static final String INVALID_OPERATION = "invalid_operation";
  public static final String INVALID_APPLICATION_ID = "invalid_application_id";
  public static final String SELF_GRANT_PROHIBITED = "self_grant_prohibited";
  public static final String INVALID_ROLE_ID = "invalid_role_id";
  public static final String REQUESTER_NOT_EXISTS = "requester_not_exists";
  public static final String EXTERNAL_USER_ACTION_PROHIBITED = "external_user_action_prohibited";
  public static final String DIFFERENT_ORG_GRANT_PROHIBITED = "different_org_grant_prohibited";
  public static final String MISSING_KEY_ATTRIBUTE = "missing_key_attribute";
  public static final String INVALID_REQUEST_PARAMETER = "invalid_request_parameter";
  public static final String TERMS_CONDITIONS_REQUIRED = "terms_condition_required";
  public static final String UNKNOWN_STATE = "unknown_state";
  public static final String UPSTREAM_TIMEOUT = "UPSTREAM_TIMEOUT";
  public static final String UPSTREAM_CONNECTION_ERROR = "UPSTREAM_CONNECTION_ERROR";

  // Authorization codes, from jwt_validation.py rather than constants.py.
  // The frontend matches on PERMISSION_REQUIRED - see
  // frontend/src/constants/ApiErrorCodes.ts.
  public static final String PERMISSION_REQUIRED = "permission_required_for_operation";
  public static final String GROUPS_REQUIRED = "authorization_groups_required";
  public static final String INVALID_OIDC_CLIENT = "invalid_oidc_client";
}
