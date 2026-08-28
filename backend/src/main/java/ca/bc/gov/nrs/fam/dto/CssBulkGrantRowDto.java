package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.UserType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One line of a bulk grant upload, as the confirmation table shows it.
 *
 * <p>The file carries two columns - a username and a role code - because that is
 * all a person can reasonably be asked to produce. Everything else here is
 * resolved: the person's name and GUID from the directory, the role's display
 * name from its sidecar. The point is that the uploader confirms <em>people and
 * roles</em>, not identifiers they cannot check by eye.
 *
 * <p>A row that cannot be granted still appears, carrying {@link #error}. Showing
 * only the good rows would let somebody submit a file believing all of it
 * applied.
 */
public record CssBulkGrantRowDto(
    /** 1-based line in the uploaded file, so an error can be found in it. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int lineNumber,

    /**
     * The GUID the grant is made with, resolved from the username.
     *
     * <p>Null on a row that resolved to nobody - the file does not carry one, and
     * this is the only place it comes from. CSS provisions a user by GUID, so a
     * row without one cannot be granted and is never valid.
     */
    String userGuid,

    /** The role code exactly as written in the file. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleCode,

    /** Which directory the username was found in. Null when it was not found. */
    UserType userType,

    /**
     * The username: as the directory spells it once resolved, and exactly as the
     * file wrote it when it was not.
     *
     * <p>Always present, because it is what the row <em>is</em> - the one thing a
     * reader can use to find the offending line in their spreadsheet.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String userName,

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

    /**
     * The region this row grants for, upper case - e.g. {@code CARIBOO}.
     *
     * <p>Blank unless the role is region scoped, and independent of the district
     * column: a role may be scoped by either, or by both at once.
     */
    String region,

    /** The region's name, resolved so the confirmation reads as a place. */
    String regionName,

    /** Zero-padded to eight digits, whatever the file wrote. */
    String forestClientNumber,

    /** The organisation's name, resolved so the confirmation is checkable. */
    String forestClientName,

    /** True when this row would be granted as it stands. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean valid,

    /**
     * True when the person already holds exactly this grant.
     *
     * <p>Not {@link #valid}, because there is nothing to do: re-granting is a
     * no-op in CSS but it emails the person again and writes an audit row for a
     * change that did not happen. Kept apart from an error, because nothing is
     * wrong - the file simply says something that is already true, which is the
     * normal shape of a file re-uploaded after a partial run.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean alreadyGranted,

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
      int lineNumber, String userName, String roleCode,
      String district, String region, String forestClientNumber, String error) {

    // No GUID: it is resolved from the username, and on this path either the
    // lookup found nobody or the row failed before it was reached.
    return new CssBulkGrantRowDto(lineNumber, null, roleCode,
        // userType - not resolved. userName is what the file wrote.
        null, userName,
        // firstName, lastName, email, organization, roleDisplayName - none of
        // which could be resolved either.
        null, null, null, null, null,
        blankToNull(district), null, blankToNull(region), null,
        blankToNull(forestClientNumber), null,
        false, false, error);
  }

  /**
   * The same row, marked as needing nothing done to it.
   *
   * <p>Everything resolved - the person, the role, the scope all read normally,
   * because they are all real. Only the outcome differs.
   */
  public CssBulkGrantRowDto asAlreadyGranted() {
    return new CssBulkGrantRowDto(
        lineNumber, userGuid, roleCode, userType, userName, firstName, lastName, email,
        organization, roleDisplayName, district, districtName, region, regionName,
        forestClientNumber, forestClientName, false, true, null);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
