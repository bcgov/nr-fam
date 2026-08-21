package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.constants.UserType;

/**
 * Formats the value written to {@code create_user} and {@code update_user}.
 *
 * <p>A person is identified as <code>&lt;TYPE&gt;\&lt;GUID&gt;</code>, for
 * example {@code IDIR\A1B2C3D4E5F60718293A4B5C6D7E8F90}. The GUID alone would be
 * ambiguous: IDIR and Business BCeID are separate directories that can issue the
 * same-looking identifier, they are searched with different tools, and an audit
 * column has no accompanying type column to disambiguate it. The prefix carries
 * that context with the value.
 *
 * <p>The prefix is the identity provider's name - {@code IDIR} or
 * {@code BCEID_BUS} - and {@code performer_user} and {@code target_user} carry
 * the same form, so the audit table speaks of people one way throughout. That is
 * also why neither needs a separate type column beside it.
 *
 * <p>Rows written by FAM itself rather than by a person - provisioning at login,
 * scheduled refreshes - get {@link FamConstants#SYSTEM_ACCOUNT_NAME}. Those have
 * no directory to name, and inventing one would misattribute the write.
 */
public final class AuditUser {

  private AuditUser() {}

  /**
   * Separates the type from the GUID. A backslash, matching how BC Gov names a
   * principal in its directory ({@code IDIR\jsmith}), so the shape reads as an
   * account rather than as one long token.
   */
  public static final String SEPARATOR = "\\";

  /** @return {@code IDIR\<guid>}, or {@code system} if either part is missing. */
  public static String of(UserType userType, String userGuid) {
    if (userType == null || userGuid == null || userGuid.isBlank()) {
      return system();
    }
    return userType.getCode() + SEPARATOR + userGuid.trim().toUpperCase(java.util.Locale.ROOT);
  }

  /**
   * The requester's audit identity.
   *
   * @return {@code system} for a null requester, which is how an unauthenticated
   *     or internal caller reaches the write path
   */
  public static String of(Requester requester) {
    return requester == null ? system() : of(requester.userType(), requester.userGuid());
  }

  /** What FAM writes when no person is responsible for the row. */
  public static String system() {
    return FamConstants.SYSTEM_ACCOUNT_NAME;
  }
}
