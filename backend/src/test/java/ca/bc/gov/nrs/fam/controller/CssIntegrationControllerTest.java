package ca.bc.gov.nrs.fam.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ca.bc.gov.nrs.fam.dto.CssRoleCreateRequest;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRevokeRequest;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.service.CssIntegrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The guards on the CSS endpoints.
 *
 * <p>Role creation is the one operation here that is not per-application: it
 * changes what an application's roles mean rather than who holds them.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CssIntegrationController guards")
class CssIntegrationControllerTest {

  private static final int INTEGRATION = 22264;
  private static final String ENV = "dev";

  @Mock private CssIntegrationService cssIntegrationService;
  @Mock private AuthorizationService authorizationService;
  @InjectMocks private CssIntegrationController controller;

  private final Requester requester = Requester.builder().userName("JSMITH").build();

  private static CssUserRoleRevokeRequest revokeRequest(String roleName) {
    return new CssUserRoleRevokeRequest(
        "AABB1122", ca.bc.gov.nrs.fam.constants.UserType.IDIR, roleName, java.util.List.of());
  }

  private static CssRoleCreateRequest createRequest() {
    return new CssRoleCreateRequest("FREP_ADMINISTRATOR", "FREP Administrator", null, false, false, false);
  }

  @Test
  @DisplayName("creating a role requires authority over this application's roles")
  void creatingARoleRequiresRoleManagement() {
    // FAM administrators, or a DevOps administrator of this exact application
    // and environment - see AuthorizationService.requireRoleManagement.
    controller.createCssApplicationRole(INTEGRATION, ENV, createRequest(), requester);

    verify(authorizationService).requireRoleManagement(requester, INTEGRATION, ENV);
  }

  @Test
  @DisplayName("does not create the role when the caller may not manage them")
  void refusedCallerCreatesNothing() {
    // The guard has to run before the service, not alongside it.
    doThrow(FamHttpException.forbidden("permission_required", "no"))
        .when(authorizationService).requireRoleManagement(any(), anyInt(), anyString());

    assertThatThrownBy(() ->
        controller.createCssApplicationRole(INTEGRATION, ENV, createRequest(), requester))
        .isInstanceOf(FamHttpException.class);

    verify(cssIntegrationService, never()).createRole(anyInt(), anyString(), any(), any());
  }

  @Test
  @DisplayName("defining a role in every environment leaves the check to the service")
  void allEnvironmentsChecksInTheService() {
    /*
        The environments are not known until the integration has been read, and
        this endpoint deliberately names none - so the controller only requires a
        caller, and the service requires authority over each environment it is
        about to write to.
    */
    controller.createCssApplicationRoleInAllEnvironments(INTEGRATION, createRequest(), requester);

    verify(authorizationService).authorize(requester);
    verify(authorizationService, never()).authorizeByFamAdmin(requester);
  }

  @Test
  @DisplayName("an application administrator's per-application access is not enough")
  void perApplicationAccessIsNotEnough() {
    // requireApplicationAccess is what the rest of this controller uses, and it
    // is satisfied by all three tiers. Creating a role must not settle for it.
    controller.createCssApplicationRole(INTEGRATION, ENV, createRequest(), requester);

    verify(authorizationService, never())
        .requireApplicationAccess(any(), anyInt(), anyString());
  }

  @Test
  @DisplayName("revoking requires administering that application")
  void revokingRequiresApplicationAccess() {
    controller.deleteCssUserRoleAssignment(INTEGRATION, ENV, revokeRequest("R"), requester);

    verify(authorizationService).requireApplicationAccess(requester, INTEGRATION, ENV);
  }

  @Test
  @DisplayName("revoking a FAM administrative role needs the appointing tier")
  void revokingAnAdminRoleNeedsTheStricterRule() {
    // Taking somebody's APP_ADMIN away is as much an act of administration as
    // granting it, so a delegated admin must not be able to do it.
    controller.deleteCssUserRoleAssignment(
        INTEGRATION, ENV, revokeRequest("APP_ADMIN_22264_DEV"), requester);

    verify(authorizationService).requireDelegatedAdminManagement(requester, INTEGRATION, ENV);
  }

  @Test
  @DisplayName("revoking an ordinary role does not need the appointing tier")
  void revokingAnOrdinaryRoleDoesNot() {
    controller.deleteCssUserRoleAssignment(INTEGRATION, ENV, revokeRequest("R"), requester);

    verify(authorizationService, never())
        .requireDelegatedAdminManagement(any(), anyInt(), anyString());
  }

  @Test
  @DisplayName("listing roles is per-application, not FAM_ADMIN only")
  void listingRolesIsPerApplication() {
    /*
        Read access belongs to any tier for that application, and to a DevOps
        administrator who administers none of it but defines its roles - two
        different jobs needing the same listing. Only creating and deleting are
        reserved.
    */
    controller.getCssApplicationRoles(INTEGRATION, ENV, requester);

    verify(authorizationService).requireRoleVisibility(requester, INTEGRATION, ENV);
    verify(authorizationService, never()).authorizeByFamAdmin(any());
  }

  @Test
  @DisplayName("counting who holds each role reads on the same rule")
  void memberCountsReadOnTheSameRule() {
    // A DevOps administrator about to delete a role needs to know what it would
    // take with it.
    controller.getCssApplicationRoleMemberCounts(INTEGRATION, ENV, requester);

    verify(authorizationService).requireRoleVisibility(requester, INTEGRATION, ENV);
  }

  // ---------------------------------------------------------------------------
  // DevOps administrators
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("reading the DevOps roster is a FAM administrator's to do")
  void readingDevopsRosterNeedsFamAdmin() {
    // It matches who may change it. The other tiers stay open to application
    // administrators, who appoint into them.
    controller.getCssApplicationAdministrators(
        INTEGRATION, ENV, ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup.DEVOPS_ADMIN,
        requester);

    verify(authorizationService).requireDevopsAdminManagement(requester);
    verify(authorizationService, never())
        .requireDelegatedAdminManagement(any(), anyInt(), anyString());
  }

  @Test
  @DisplayName("reading the other rosters is not narrowed to FAM administrators")
  void readingOtherRostersIsUnchanged() {
    controller.getCssApplicationAdministrators(
        INTEGRATION, ENV, ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup.APP_ADMIN,
        requester);

    verify(authorizationService).requireDelegatedAdminManagement(requester, INTEGRATION, ENV);
    verify(authorizationService, never()).requireDevopsAdminManagement(any());
  }
}
