package ca.bc.gov.nrs.fam.dto;

/**
 * The outcome for one role-and-client pairing within a delegated-admin grant.
 *
 * <p>Partial success, as with end-user grants: one inactive client does not fail
 * the rest of the request.
 */
import io.swagger.v3.oas.annotations.media.Schema;

public record FamAccessControlPrivilegeCreateResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int statusCode,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FamAccessControlPrivilegeGetResponse detail,
    String errorMessage) {

  public boolean isSuccess() {
    return statusCode == 200;
  }
}
