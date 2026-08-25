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

    /**
     * The district this row grants for, upper case - e.g. {@code DCC}.
     *
     * <p>Blank unless the role is district scoped. A row may carry a district
     * <em>and</em> a forest client: a role scoped both ways is granted per pair,
     * and the file expresses that as one row per pair.
     */
    String district,

    /** The district's name, resolved so the confirmation reads as a place. */
    String districtName,

    /** Zero-padded to eight digits, whatever the file wrote. */
    String forestClientNumber,

    /** The organisation's name, resolved so the confirmation is checkable. */
    String forestClientName,

    /** True when this row would be granted as it stands. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean valid,

    /** Why the row cannot be granted. Null when {@link #valid}. */
    String error) {

  /**
   * A row that failed before anything could be resolved.
   *
   * <p>The scope values are carried through even on a rejection, because half of
   * what can go wrong is <em>about</em> them - a district on a role that takes
   * none, a client number that does not exist - and an error naming a value the
   * row no longer displays is not something anybody can act on.
   */
  public static CssBulkGrantRowDto invalid(
      int lineNumber, String userGuid, String roleCode,
      String district, String forestClientNumber, String error) {

    return new CssBulkGrantRowDto(lineNumber, userGuid, roleCode,
        // userType, userName, firstName, lastName, email, organization,
        // roleDisplayName - none of which could be resolved.
        null, null, null, null, null, null, null,
        blankToNull(district), null, blankToNull(forestClientNumber), null,
        false, error);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
