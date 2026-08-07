package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.EmailSendingStatus;
import java.util.List;

/**
 * All outcomes for one delegated-admin grant request.
 *
 * <p>Unlike the end-user grant response, the email status is reported once for
 * the whole request rather than per assignment - a delegated admin is notified
 * with a single email covering every role granted.
 */
import io.swagger.v3.oas.annotations.media.Schema;

public record FamAccessControlPrivilegeResponse(
    EmailSendingStatus emailSendingStatus,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FamAccessControlPrivilegeCreateResponse> assignmentsDetail) {}
