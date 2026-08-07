package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.constants.AppEnv;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.TargetUser;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.repository.FamAccessControlPrivilegeRepository;
import ca.bc.gov.nrs.fam.service.ApplicationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The endpoint guards from {@code router_guards.py}.
 *
 * <p>These run before any handler logic, exactly as the FastAPI {@code Depends}
 * guards did. They are kept out of the service layer on purpose: services assume
 * authorisation has already been settled.
 *
 * <p>Guards that only need the requester and the already-resolved target users
 * live here. {@code enforce_bceid_by_same_org_guard} does not: it has to call
 * IDIM to verify the target users before it can compare organisations, so it sits
 * in {@link ca.bc.gov.nrs.fam.service.TargetUserValidationService} alongside that
 * verification.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizationService {

  private final ApplicationService applicationService;
  private final FamAccessControlPrivilegeRepository accessControlPrivilegeRepository;

  /**
   * The general check used by the Forest Client and IDIM proxy endpoints: the
   * caller must administer at least one application, in either capacity.
   *
   * <p>Port of {@code router_guards.authorize}.
   */
  public void authorize(Requester requester) {
    boolean hasAdminGroups = requester.accessRoles() != null && !requester.accessRoles().isEmpty();
    if (!hasAdminGroups && !requester.isDelegatedAdmin()) {
      throw FamHttpException.forbidden(
          ErrorCode.GROUPS_REQUIRED, "At least one access group is required.");
    }
  }

  /**
   * The caller must be an application admin or a delegated admin of this specific
   * application.
   *
   * <p>Port of {@code router_guards.authorize_by_app_id}.
   */
  @Transactional(readOnly = true)
  public void authorizeByAppId(Long applicationId, Requester requester) {
    if (applicationService.isAppAdmin(applicationId, requester)) {
      return;
    }

    boolean delegatedAdminOfApp = !accessControlPrivilegeRepository
        .findManagedRoleIds(requester.userId(), applicationId).isEmpty();

    if (!delegatedAdminOfApp) {
      throw FamHttpException.forbidden(ErrorCode.PERMISSION_REQUIRED,
          "Requester has no admin or delegated admin access to the application.");
    }
  }

  /**
   * Same check, but starting from a role rather than an application id.
   *
   * <p>Port of {@code router_guards.authorize_by_application_role}.
   */
  @Transactional(readOnly = true)
  public void authorizeByApplicationRole(FamRole role, Requester requester) {
    authorizeByAppId(role.getApplication().getApplicationId(), requester);
  }

  /**
   * A delegated admin may only act on roles they have been granted privilege over.
   * Application admins bypass this.
   *
   * <p>Port of the concrete-role branch of {@code router_guards.authorize_by_privilege}.
   * The abstract-role branch, which resolves a forest-client-scoped child role
   * first, is handled by the user-role assignment service where the requested
   * client numbers are available.
   */
  @Transactional(readOnly = true)
  public void authorizeByPrivilege(FamRole role, Requester requester) {
    if (applicationService.isAppAdmin(role.getApplication().getApplicationId(), requester)) {
      return;
    }
    requirePrivilegeOnRole(requester, role.getRoleId());
  }

  /** Throws unless the requester is a delegated admin for exactly this role. */
  @Transactional(readOnly = true)
  public void requirePrivilegeOnRole(Requester requester, Long roleId) {
    boolean hasPrivilege = accessControlPrivilegeRepository
        .findByUserUserIdAndRoleRoleId(requester.userId(), roleId).isPresent();
    if (!hasPrivilege) {
      throw FamHttpException.forbidden(
          ErrorCode.PERMISSION_REQUIRED, "Requester has no privilege to grant this access.");
    }
  }

  /**
   * A Business BCeID delegated admin must have accepted the current terms before
   * doing anything.
   *
   * <p>Port of {@code router_guards.enforce_bceid_terms_conditions_guard}. Note
   * this returns HTTP 400, not 403 - the frontend keys off the error code to show
   * the terms dialog.
   */
  public void enforceBceidTermsConditions(Requester requester) {
    if (requester.requiresAcceptTc()) {
      throw FamHttpException.badRequest(
          ErrorCode.TERMS_CONDITIONS_REQUIRED, "Requires to accept terms and conditions.");
    }
  }

  /** IDIR-only endpoints. Port of {@code router_guards.internal_only_action}. */
  public void internalOnlyAction(Requester requester) {
    if (requester.userType() != UserType.IDIR) {
      throw FamHttpException.forbidden(ErrorCode.EXTERNAL_USER_ACTION_PROHIBITED,
          "Action is not allowed for external user.");
    }
  }

  /**
   * Endpoints only a Business BCeID delegated admin should reach - accepting terms
   * and conditions is meaningless for anyone else.
   *
   * <p>Port of {@code router_guards.external_delegated_admin_only_action}.
   */
  public void externalDelegatedAdminOnlyAction(Requester requester) {
    if (!requester.isExternalDelegatedAdmin()) {
      throw FamHttpException.forbidden(ErrorCode.INVALID_OPERATION, "Action is not needed");
    }
  }

  /**
   * Whether an app admin may alter their own access to this role.
   *
   * <p>Port of {@code router_guards._is_self_grant_exempt}. Fails closed three
   * ways: never for FAM itself, never for an application whose environment is
   * missing or unrecognised, and never for a delegated admin.
   */
  @Transactional(readOnly = true)
  public boolean isSelfGrantExempt(FamRole role, Requester requester) {
    FamApplication application = role.getApplication();

    if (FamConstants.APPLICATION_FAM.equals(application.getApplicationName())) {
      return false;
    }

    boolean envAllowsSelfGrant = AppEnv.fromCode(application.getAppEnvironment())
        .map(AppEnv.SELF_GRANT_ALLOWED::contains)
        .orElse(false);
    if (!envAllowsSelfGrant) {
      return false;
    }

    return requester.isAdminOf(application.getApplicationName());
  }

  /**
   * Only a FAM administrator may pass.
   *
   * <p>Port of {@code admin_management/router_guards.authorize_by_fam_admin}.
   * Administering <em>who administers an application</em> is FAM-wide authority,
   * so an application admin is not sufficient.
   */
  public void authorizeByFamAdmin(Requester requester) {
    if (!requester.isAdminOf(FamConstants.APPLICATION_FAM)) {
      throw FamHttpException.forbidden(ErrorCode.PERMISSION_REQUIRED,
          "Requester has no FAM admin access.");
    }
  }

  /**
   * Nobody may change their own access, unless they are an application admin
   * acting on a DEV or TEST instance of another application.
   *
   * <p>Port of {@code router_guards.enforce_self_grant_guard}. Identity is matched
   * on type plus GUID, never on user name.
   *
   * @throws FamHttpException 403 {@code self_grant_prohibited}
   */
  @Transactional(readOnly = true)
  public void enforceSelfGrant(
      Requester requester, List<TargetUser> targetUsers, FamRole role) {

    for (TargetUser targetUser : targetUsers) {
      boolean isSelf = java.util.Objects.equals(
              requester.userType() == null ? null : requester.userType().getCode(),
              targetUser.userTypeCode())
          && java.util.Objects.equals(requester.userGuid(), targetUser.userGuid());

      if (!isSelf) {
        continue;
      }

      if (isSelfGrantExempt(role, requester)) {
        log.info("Self-grant/revoke allowed: app admin '{}' acting on own access for "
                + "dev/test app '{}', role '{}'.",
            requester.userName(), role.getApplication().getApplicationName(), role.getRoleName());
        return;
      }

      throw FamHttpException.forbidden(ErrorCode.SELF_GRANT_PROHIBITED,
          "Altering permission privilege to self is not allowed.");
    }
  }

  /**
   * Nobody may alter their own administrator privileges - with no exemption.
   *
   * <p>Port of the admin-management {@code enforce_self_grant_guard}, which is
   * deliberately stricter than the end-user one. Self-granting an end-user role on
   * a DEV or TEST application is a convenience; self-granting delegated-admin or
   * app-admin authority is a trust escalation, so it is blocked everywhere,
   * including on dev and test applications.
   *
   * @throws FamHttpException 403 {@code self_grant_prohibited}
   */
  public void enforceSelfGrantUnconditional(Requester requester, List<TargetUser> targetUsers) {
    for (TargetUser targetUser : targetUsers) {
      boolean isSelf = java.util.Objects.equals(
              requester.userType() == null ? null : requester.userType().getCode(),
              targetUser.userTypeCode())
          && java.util.Objects.equals(requester.userGuid(), targetUser.userGuid());

      if (isSelf) {
        throw FamHttpException.forbidden(ErrorCode.SELF_GRANT_PROHIBITED,
            "Altering permission privilege to self is not allowed.");
      }
    }
  }

  /**
   * A Business BCeID admin may not manage an IDIR user's access.
   *
   * <p>Port of {@code router_guards.authorize_by_user_type}. An IDIR requester is
   * unrestricted; the rule is one-directional.
   *
   * @throws FamHttpException 500 if a target user has no identity type - that is a
   *     data problem, not a permission problem, so it is not reported as 403
   */
  public void authorizeByUserType(Requester requester, List<TargetUser> targetUsers) {
    if (requester.userType() != UserType.BCEID) {
      return;
    }

    for (TargetUser targetUser : targetUsers) {
      String targetType = targetUser.userTypeCode();
      if (targetType == null) {
        throw FamHttpException.internalError(ErrorCode.MISSING_KEY_ATTRIBUTE,
            "Operation encountered unexpected error. Target user user_type code is missing.");
      }
      if (UserType.IDIR.getCode().equals(targetType)) {
        throw FamHttpException.forbidden(ErrorCode.PERMISSION_REQUIRED,
            "Business BCEID requester has no privilege to grant this access to IDIR user.");
      }
    }
  }
}
