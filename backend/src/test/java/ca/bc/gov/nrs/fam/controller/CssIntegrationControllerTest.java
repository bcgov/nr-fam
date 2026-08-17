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

  private final Requester requester = Requester.builder().userId(1L).userName("JSMITH").build();

  private static CssUserRoleRevokeRequest revokeRequest(String roleName) {
    return new CssUserRoleRevokeRequest(
        "AABB1122", ca.bc.gov.nrs.fam.constants.UserType.IDIR, roleName, null, null);
  }

  private static CssRoleCreateRequest createRequest() {
    return new CssRoleCreateRequest("FREP_ADMINISTRATOR", "FREP Administrator", false, false);
  }

  @Test
  @DisplayName("creating a role requires FAM_ADMIN")
  void creatingARoleRequiresFamAdmin() {
    controller.createCssApplicationRole(INTEGRATION, ENV, createRequest(), requester);

    verify(authorizationService).authorizeByFamAdmin(requester);
  }

  @Test
  @DisplayName("does not create the role when the caller is not a FAM administrator")
  void refusedCallerCreatesNothing() {
    // The guard has to run before the service, not alongside it.
    doThrow(FamHttpException.forbidden("permission_required", "no"))
        .when(authorizationService).authorizeByFamAdmin(any());

    assertThatThrownBy(() ->
        controller.createCssApplicationRole(INTEGRATION, ENV, createRequest(), requester))
        .isInstanceOf(FamHttpException.class);

    verify(cssIntegrationService, never()).createRole(anyInt(), anyString(), any(), any());
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
    // Read access belongs to any tier for that application; only creation is
    // reserved.
    controller.getCssApplicationRoles(INTEGRATION, ENV, requester);

    verify(authorizationService).requireApplicationAccess(requester, INTEGRATION, ENV);
    verify(authorizationService, never()).authorizeByFamAdmin(any());
  }
}
