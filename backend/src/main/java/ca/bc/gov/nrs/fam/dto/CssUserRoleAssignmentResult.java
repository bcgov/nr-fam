package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.EmailSendingStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The outcome for one role in a grant.
 *
 * <p>A scoped grant produces one of these per scope value. They are reported
 * individually because a grant can partly succeed: one district's role may be
 * created and assigned while another fails.
 */
public record CssUserRoleAssignmentResult(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleName,

    /** True when this call created the role, false when it already existed. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean roleCreated,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean assigned,

    /** Null on success. */
    String errorMessage,

    /**
     * Whether the "you have been granted access" email reached the mail relay.
     *
     * <p>Carried on the result rather than thrown, matching upstream: a grant
     * that succeeded is not undone by a notification that did not, and the
     * administrator needs to see both outcomes separately.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    EmailSendingStatus emailSendingStatus) {

  public static CssUserRoleAssignmentResult failed(String roleName, String message) {
    return new CssUserRoleAssignmentResult(
        roleName, false, false, message, EmailSendingStatus.NOT_REQUIRED);
  }

  /** The same result with its email outcome recorded. */
  public CssUserRoleAssignmentResult withEmailStatus(EmailSendingStatus status) {
    return new CssUserRoleAssignmentResult(roleName, roleCreated, assigned, errorMessage, status);
  }
}
