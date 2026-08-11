package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.constants.UserType;
import java.util.List;
import java.util.Optional;
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
   * Whether this caller administers everything.
   *
   * <p>{@code FAM_ADMIN} is the only role that is not scoped to an application.
   */
  public boolean isFamAdmin() {
    return holds(FamAdminRole.FAM_ADMIN);
  }

  /**
   * Whether this caller may administer the given CSS application, and at what
   * level.
   *
   * <p>Highest tier wins: a FAM administrator is treated as an application
   * administrator everywhere, so callers can compare tiers without also having to
   * special-case {@code FAM_ADMIN}.
   *
   * @return empty when the caller has no authority over this application at all
   */
  public Optional<AdminRoleAuthGroup> tierFor(int cssIntegrationId, String cssEnvironment) {
    if (isFamAdmin()) {
      return Optional.of(AdminRoleAuthGroup.FAM_ADMIN);
    }
    if (holds(FamAdminRole.appAdmin(cssIntegrationId, cssEnvironment))) {
      return Optional.of(AdminRoleAuthGroup.APP_ADMIN);
    }
    if (holds(FamAdminRole.delegatedAdmin(cssIntegrationId, cssEnvironment))) {
      return Optional.of(AdminRoleAuthGroup.DELEGATED_ADMIN);
    }
    return Optional.empty();
  }

  /** May grant and revoke user access for this application. All three tiers may. */
  public boolean canManageAccess(int cssIntegrationId, String cssEnvironment) {
    return tierFor(cssIntegrationId, cssEnvironment).isPresent();
  }

  /**
   * May appoint or remove delegated administrators for this application.
   *
   * <p>Deliberately not granted to a delegated administrator: that is the one
   * thing separating the two tiers, and without it a delegated admin could
   * promote themselves or anyone else and the distinction would be decorative.
   */
  public boolean canManageDelegatedAdmins(int cssIntegrationId, String cssEnvironment) {
    return tierFor(cssIntegrationId, cssEnvironment)
        .filter(tier -> tier == AdminRoleAuthGroup.FAM_ADMIN
            || tier == AdminRoleAuthGroup.APP_ADMIN)
        .isPresent();
  }

  /** Case-insensitive: CSS role names are free text and casing is easy to get wrong. */
  private boolean holds(String roleName) {
    return accessRoles != null
        && accessRoles.stream().anyMatch(held -> held != null && held.equalsIgnoreCase(roleName));
  }
}
