package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.constants.UserType;
import java.util.List;
import lombok.Builder;

/**
 * The authenticated caller, resolved once per request from the access token plus
 * the matching {@code fam_user} row.
 *
 * <p>Port of {@code schemas/requester.py}. Every guard and most services need it,
 * so it is resolved by {@link RequesterService} and injected into controllers via
 * {@link CurrentRequester}.
 *
 * @param oidcUserId the token subject. Called {@code cognito_user_id} in the
 *     schema for historical reasons; it is now the Keycloak subject.
 * @param accessRoles roles carried on the token, e.g. {@code FOM_DEV_ADMIN}.
 *     Used by the app-admin check.
 * @param isDelegatedAdmin whether this user is a delegated admin of any
 *     application. Not scoped to one application - that check is per role.
 * @param requiresAcceptTc whether the user must accept the current terms and
 *     conditions before acting.
 */
@Builder(toBuilder = true)
public record Requester(
    Long userId,
    String oidcUserId,
    String userName,
    String firstName,
    String lastName,
    String email,
    UserType userType,
    String userGuid,
    String businessGuid,
    List<String> accessRoles,
    boolean isDelegatedAdmin,
    boolean requiresAcceptTc) {

  /**
   * A Business BCeID user acting as a delegated admin.
   *
   * <p>These callers are the most restricted: they must accept terms and
   * conditions, and may only see or manage users in their own organisation.
   */
  public boolean isExternalDelegatedAdmin() {
    return userType == UserType.BCEID && isDelegatedAdmin;
  }

  public boolean isBceid() {
    return userType == UserType.BCEID;
  }

  public boolean isIdir() {
    return userType == UserType.IDIR;
  }

  /**
   * Whether this caller holds the admin role for the given application.
   *
   * <p>Port of {@code crud_utils.is_app_admin}: the token must carry
   * {@code <APPLICATION_NAME>_ADMIN}, upper-cased.
   */
  public boolean isAdminOf(String applicationName) {
    if (accessRoles == null || applicationName == null) {
      return false;
    }
    return accessRoles.contains(applicationName.toUpperCase() + "_ADMIN");
  }
}
