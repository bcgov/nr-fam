package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.UserType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One line of a bulk grant upload, as the confirmation table shows it.
 *
 * <p>The file carries two columns - a GUID and a role code - because that is all
 * a person can reasonably be asked to produce. Everything else here is resolved:
 * the name from the directory, the role's display name from its sidecar. The
 * point is that the uploader confirms <em>people and roles</em>, not identifiers
 * they cannot check by eye.
 *
 * <p>A row that cannot be granted still appears, carrying {@link #error}. Showing
 * only the good rows would let somebody submit a file believing all of it
 * applied.
 */
public record CssBulkGrantRowDto(
    /** 1-based line in the uploaded file, so an error can be found in it. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int lineNumber,

    /** The GUID exactly as written in the file. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String userGuid,

    /** The role code exactly as written in the file. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleCode,

    /** Which directory the GUID was found in. Null when it was not found. */
    UserType userType,

    /** IDIR or BCeID username, once resolved. */
    String userName,

    String firstName,
    String lastName,
    String email,

    /** The organisation, for a Business BCeID user. */
    String organization,

    /** The role's short name, e.g. "View All". Null for a role with no sidecar. */
    String roleDisplayName,

    /** True when this row would be granted as it stands. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean valid,

    /** Why the row cannot be granted. Null when {@link #valid}. */
    String error) {

  /** A row that failed before anything could be resolved. */
  public static CssBulkGrantRowDto invalid(
      int lineNumber, String userGuid, String roleCode, String error) {

    return new CssBulkGrantRowDto(lineNumber, userGuid, roleCode,
        null, null, null, null, null, null, null, false, error);
  }
}
