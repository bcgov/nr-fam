package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.UserType;
import jakarta.annotation.PostConstruct;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The request guards.
 *
 * <p>Reduced to what still has meaning once roles and role assignments moved to
 * CSS. The guards that took a {@code FamRole} or a FAM application id are gone
 * with the tables behind them; what remains is decided entirely from the
 * requester's token.
 *
 * <p>The self-grant guards went with them. They protected FAM's own grant path,
 * which no longer exists: a grant is a CSS role assignment now. If self-grant is
 * to be prevented, it has to be enforced on the CSS assignment endpoint - see the
 * note on {@link #forbidSelfGrant}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizationService {

  private final FamProperties famProperties;

  @PostConstruct
  void warnIfOwnIntegrationUnknown() {
    if (ownIntegrationId() == null) {
      log.warn("fam.integration.css.own-integration-id is not set. FAM's own CSS "
          + "integration cannot be identified, so it is administrable by any "
          + "APP_ADMIN or DELEGATED_ADMIN holding a role for it rather than by "
          + "FAM_ADMIN alone. Set CSS_OWN_INTEGRATION_ID.");
    }
  }

  private Integer ownIntegrationId() {
    if (famProperties.integration() == null || famProperties.integration().css() == null) {
      return null;
    }
    return famProperties.integration().css().ownIntegrationId();
  }

  /**
   * Whether FAM's own integration is the one being administered.
   *
   * <p>Administering FAM means deciding who administers every other application,
   * so it is not something an application administrator should reach.
   */
  private boolean isOwnIntegration(int cssIntegrationId) {
    Integer own = ownIntegrationId();
    return own != null && own == cssIntegrationId;
  }

  /**
   * Whether this caller may administer this application at all.
   *
   * <p>The single predicate behind both the per-request guards and the filtering
   * of the application list. They must not diverge: a list that offers an
   * application the guards would refuse is confusing, and a list that hides one
   * the guards would allow is a hole waiting to be found by anyone who knows the
   * integration id.
   */
  public boolean canAdminister(Requester requester, int cssIntegrationId, String cssEnvironment) {
    if (isOwnIntegration(cssIntegrationId)) {
      return requester.isFamAdmin();
    }
    return requester.canManageAccess(cssIntegrationId, cssEnvironment);
  }

  /**
   * The general check: the caller must administer something.
   *
   * <p>Port of {@code router_guards.authorize}. Admin rights are roles on the
   * token now, so an empty role list means the caller administers nothing.
   */
  public void authorize(Requester requester) {
    boolean hasAdminGroups = requester.accessRoles() != null && !requester.accessRoles().isEmpty();
    if (!hasAdminGroups) {
      throw FamHttpException.forbidden(
          ErrorCode.GROUPS_REQUIRED, "At least one access group is required.");
    }
  }

  /** IDIR-only endpoints. Port of {@code router_guards.internal_only_action}. */
  public void internalOnlyAction(Requester requester) {
    if (requester.userType() != UserType.IDIR) {
      throw FamHttpException.forbidden(ErrorCode.EXTERNAL_USER_ACTION_PROHIBITED,
          "Action is not allowed for external user.");
    }
  }

  /** The caller must be a FAM super administrator. */
  public void authorizeByFamAdmin(Requester requester) {
    if (!requester.isFamAdmin()) {
      throw FamHttpException.forbidden(
          ErrorCode.PERMISSION_REQUIRED, "Requires FAM administrator privilege.");
    }
  }

  /**
   * The caller must administer this application in some capacity.
   *
   * <p>Satisfied by any of the three tiers. This is the per-application check
   * that {@link #authorize} deliberately is not: {@code authorize} only asks
   * whether the caller administers <em>something</em>, which is not enough to
   * decide whether they may act on <em>this</em> application.
   */
  public void requireApplicationAccess(
      Requester requester, int cssIntegrationId, String cssEnvironment) {

    if (!canAdminister(requester, cssIntegrationId, cssEnvironment)) {
      // Deliberately the same message either way. Saying "this is FAM's own
      // integration, you need FAM_ADMIN" would confirm which integration id is
      // FAM's to a caller who was guessing.
      throw FamHttpException.forbidden(ErrorCode.PERMISSION_REQUIRED,
          "Requires administrator privilege for this application.");
    }
  }

  /**
   * The caller must be allowed to grant or revoke these specific roles.
   *
   * <p>Port of legacy's {@code authorize_by_privilege}. An application
   * administrator passes for anything the application defines; a delegated
   * administrator passes only for the roles they hold a delegation for.
   *
   * <p><b>Checked per concrete role, after scope has been applied.</b> A grant of
   * one role across three districts is three roles, and a delegation covering one
   * district must not carry the other two - which is the whole point of
   * delegating per scope value. Passing the base role name here instead would
   * quietly authorise every district.
   *
   * <p>All or nothing: one role the caller may not grant refuses the request
   * rather than granting the rest. A partial grant would leave the caller
   * believing they had done something they had not.
   */
  public void requireGrantableRoles(
      Requester requester, int cssIntegrationId, String cssEnvironment, List<String> roleNames) {

    List<String> refused = roleNames.stream()
        .filter(roleName -> !requester.canGrantRole(cssIntegrationId, cssEnvironment, roleName))
        .toList();

    if (!refused.isEmpty()) {
      log.warn("{} may not grant {} on integration {} ({}).",
          requester.userName(), refused, cssIntegrationId, cssEnvironment);
      throw FamHttpException.forbidden(ErrorCode.PERMISSION_REQUIRED,
          "You have not been delegated %s in this application."
              .formatted(String.join(", ", refused)));
    }
  }

  /**
   * The caller must be able to appoint delegated administrators for this
   * application - FAM administrator or application administrator only.
   *
   * <p>A delegated administrator is excluded on purpose. They may grant and
   * revoke ordinary access, but not create more administrators; allowing it would
   * let them promote themselves and erase the distinction between the tiers.
   */
  public void requireDelegatedAdminManagement(
      Requester requester, int cssIntegrationId, String cssEnvironment) {

    boolean allowed = isOwnIntegration(cssIntegrationId)
        ? requester.isFamAdmin()
        : requester.canManageDelegatedAdmins(cssIntegrationId, cssEnvironment);

    if (!allowed) {
      throw FamHttpException.forbidden(ErrorCode.PERMISSION_REQUIRED,
          "Only a FAM or application administrator may manage delegated administrators.");
    }
  }

  /**
   * The caller must be able to define and remove this application's roles.
   *
   * <p>A FAM administrator anywhere, or a DevOps administrator of this exact
   * application and environment. An application administrator is excluded, which
   * is the rule that was already in force when this was FAM administrators only:
   * they hand out what the application defines without also being able to invent
   * something new for it to mean.
   */
  public void requireRoleManagement(
      Requester requester, int cssIntegrationId, String cssEnvironment) {

    if (requester == null || !requester.canManageRoles(cssIntegrationId, cssEnvironment)) {
      throw FamHttpException.forbidden(ErrorCode.PERMISSION_REQUIRED,
          "Only a FAM administrator or a DevOps administrator of this application "
              + "may manage its roles.");
    }
  }

  /**
   * The caller must be able to <em>look at</em> this application's roles.
   *
   * <p>Wider than administering access, because two different jobs need the same
   * listing: granting a role means choosing from it, and defining one means
   * seeing what is already there and how many people hold it.
   *
   * <p>A DevOps administrator manages no access, so the ordinary check refuses
   * them - and the Manage roles screen they were given could not draw its own
   * table. Reading is all this admits: creating and deleting still go through
   * {@link #requireRoleManagement}, and granting through
   * {@link #requireApplicationAccess}.
   */
  public void requireRoleVisibility(
      Requester requester, int cssIntegrationId, String cssEnvironment) {

    if (requester != null && requester.canManageRoles(cssIntegrationId, cssEnvironment)) {
      return;
    }
    // Falls through to the ordinary rule, so everybody else is refused in the
    // same words they were before.
    requireApplicationAccess(requester, cssIntegrationId, cssEnvironment);
  }

  /**
   * The caller must be able to appoint DevOps administrators - FAM administrators
   * only.
   *
   * <p>Stricter than appointing an application administrator, which a peer may
   * do. A DevOps administrator changes what an application's roles <em>are</em>,
   * and that is not authority an application administrator holds, so they cannot
   * hand it out either. Letting them would be a way to acquire it by proxy.
   */
  public void requireDevopsAdminManagement(Requester requester) {
    if (requester == null || !requester.isFamAdmin()) {
      throw FamHttpException.forbidden(ErrorCode.PERMISSION_REQUIRED,
          "Only a FAM administrator may manage DevOps administrators.");
    }
  }

  /**
   * A Business BCeID administrator may only read users from their own
   * organisation.
   *
   * <p>This used to live inside the IDIM integration, because the organisation is
   * only known once the directory has answered - the check has to happen after
   * the lookup, not before it. nr-user-lookup-api authenticates as FAM's own
   * service account and receives no requester at all, so it cannot apply the rule
   * on FAM's behalf the way IDIM could. Losing it silently would let a BCeID
   * administrator enumerate users at other organisations, so it moved here.
   *
   * <p>An IDIR requester is unrestricted. A target with no organisation is
   * refused for a BCeID requester rather than allowed: an unknown organisation is
   * not the same as a matching one.
   *
   * @param targetBusinessGuid the organisation the looked-up user belongs to
   */
  public void enforceSameOrganization(Requester requester, String targetBusinessGuid) {
    if (requester.userType() != UserType.BCEID) {
      return;
    }
    String own = requester.businessGuid();
    if (own == null || targetBusinessGuid == null || !own.equalsIgnoreCase(targetBusinessGuid)) {
      throw FamHttpException.forbidden(ErrorCode.PERMISSION_REQUIRED,
          "Operation requires business bceid users to be within the same organization");
    }
  }

  /**
   * Refuse a grant the requester is making to themselves.
   *
   * <p>Upstream had two variants of this, one of which allowed an application
   * admin to self-grant on DEV or TEST of a <em>different</em> application. That
   * exemption depended on FAM knowing an application's environment and the
   * requester's admin grants from its own tables, neither of which it does now,
   * so this is the unconditional form: a requester may never grant to themselves.
   *
   * <p>Not currently wired to the CSS assignment endpoint. Doing so needs the
   * target user's GUID compared against the requester's, which the CSS grant
   * request carries - see {@code CssUserRoleAssignmentRequest.userGuid}.
   */
  public void forbidSelfGrant(Requester requester, String targetUserGuid) {
    if (requester.userGuid() != null && requester.userGuid().equalsIgnoreCase(targetUserGuid)) {
      /*
          Said plainly, and said for every direction this guard covers: it is
          reached from granting, revoking, appointing and removing, so it cannot
          be worded as though the caller were only ever adding something.

          The sentence names the way out as well as the rule. A refusal that
          only says no leaves somebody clicking the same button again.
      */
      throw FamHttpException.forbidden(ErrorCode.SELF_GRANT_PROHIBITED,
          "You cannot change your own permissions. Ask another administrator "
              + "to do it for you.");
    }
  }
}
