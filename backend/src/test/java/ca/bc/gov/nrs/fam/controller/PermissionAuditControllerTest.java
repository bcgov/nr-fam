package ca.bc.gov.nrs.fam.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.service.PermissionAuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The guards on the audit trail.
 *
 * <p>Both endpoints are per application. The trail says who granted what to
 * whom, which is a wider answer than administering one application entitles
 * somebody to - so "administers something" is not enough, and used not to be
 * checked at all.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionAuditController guards")
class PermissionAuditControllerTest {

  private static final int INTEGRATION = 22264;
  private static final String ENV = "dev";

  @Mock private PermissionAuditService permissionAuditService;
  @Mock private AuthorizationService authorizationService;
  @InjectMocks private PermissionAuditController controller;

  private final Requester requester = Requester.builder().userName("JSMITH").build();

  @Test
  @DisplayName("reading one person's history is checked against that application")
  void historyIsCheckedPerApplication() {
    /*
        It used to ask only that the caller administered anything at all, which
        let anyone holding any tier read any application's history by naming its
        integration id.
    */
    controller.getHistory("ABC123", UserType.IDIR, INTEGRATION, ENV, requester);

    verify(authorizationService).requireApplicationAccess(requester, INTEGRATION, ENV);
    verify(authorizationService, never()).authorize(any());
  }

  @Test
  @DisplayName("a refused caller reads nothing")
  void refusedCallerReadsNothing() {
    // The guard has to run before the service, not alongside it.
    doThrow(FamHttpException.forbidden("permission_required", "no"))
        .when(authorizationService)
        .requireApplicationAccess(any(), anyInt(), anyString());

    assertThatThrownBy(() ->
        controller.getHistory("ABC123", UserType.IDIR, INTEGRATION, ENV, requester))
        .isInstanceOf(FamHttpException.class);

    verify(permissionAuditService, never())
        .getHistory(anyString(), any(), anyInt(), anyString());
  }

  @Test
  @DisplayName("listing who has history is checked the same way")
  void userListIsCheckedPerApplication() {
    // The same rule as reading the application's permissions, and for the same
    // reason: it says what has happened to access the caller already manages.
    controller.getUsers(INTEGRATION, ENV, requester);

    verify(authorizationService).requireApplicationAccess(requester, INTEGRATION, ENV);
  }

  @Test
  @DisplayName("a refused caller is offered nobody")
  void refusedCallerSeesNoUsers() {
    doThrow(FamHttpException.forbidden("permission_required", "no"))
        .when(authorizationService)
        .requireApplicationAccess(any(), anyInt(), anyString());

    assertThatThrownBy(() -> controller.getUsers(INTEGRATION, ENV, requester))
        .isInstanceOf(FamHttpException.class);

    verify(permissionAuditService, never()).getUsersWithHistory(anyInt(), anyString());
  }
}
