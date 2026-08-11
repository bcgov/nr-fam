package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ca.bc.gov.nrs.fam.constants.EmailSendingStatus;

/**
 * The outcome for one user-and-role pairing within a grant request.
 *
 * <p>A grant is deliberately partial-success: one user failing IDIM verification,
 * or one forest client being inactive, does not fail the whole batch. Each
 * outcome carries its own HTTP status, so the frontend can report per user.
 *
 * @param statusCode 200 granted, 409 already assigned, 400 rejected, 403 blocked
 *     by the organisation rule, 500 unexpected.
 * @param detail the created (or conflicting) assignment; null when nothing was
 *     created.
 */

public record FamUserRoleAssignmentCreateResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int statusCode,
    FamApplicationUserRoleAssignmentGetDto detail,
    String errorMessage,
    EmailSendingStatus emailSendingStatus) {

  public static FamUserRoleAssignmentCreateResponse failure(int statusCode, String errorMessage) {
    return new FamUserRoleAssignmentCreateResponse(
        statusCode, null, errorMessage, EmailSendingStatus.NOT_REQUIRED);
  }

  public static FamUserRoleAssignmentCreateResponse success(
      FamApplicationUserRoleAssignmentGetDto detail) {
    return new FamUserRoleAssignmentCreateResponse(
        200, detail, null, EmailSendingStatus.NOT_REQUIRED);
  }

  public FamUserRoleAssignmentCreateResponse withEmailStatus(EmailSendingStatus status) {
    return new FamUserRoleAssignmentCreateResponse(statusCode, detail, errorMessage, status);
  }

  public FamUserRoleAssignmentCreateResponse withDetail(
      FamApplicationUserRoleAssignmentGetDto newDetail) {
    return new FamUserRoleAssignmentCreateResponse(
        statusCode, newDetail, errorMessage, emailSendingStatus);
  }

  public boolean isSuccess() {
    return statusCode == 200;
  }
}
