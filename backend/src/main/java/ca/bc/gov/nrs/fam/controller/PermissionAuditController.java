package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.PermissionAuditHistoryDto;
import ca.bc.gov.nrs.fam.dto.PermissionAuditUserDto;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.service.PermissionAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Port of {@code router_permission_audit.py}. */
@RestController
@RequestMapping("/permission-audit-history")
@Tag(name = "Permission Audit")
@RequiredArgsConstructor
public class PermissionAuditController {

  private final PermissionAuditService permissionAuditService;
  private final AuthorizationService authorizationService;

  @GetMapping
  @Operation(operationId = "get_permission_audit_history_by_user_and_application", summary = "Permission audit history for a user within an application")
  public List<PermissionAuditHistoryDto> getHistory(
      @RequestParam String targetUserGuid,
      @RequestParam UserType targetUserType,
      @RequestParam Integer cssIntegrationId,
      @RequestParam String cssEnvironment,
      Requester requester) {

    /*
        Per application, not merely "administers something".

        This used to ask only that the caller administered anything at all, which
        let anyone holding any tier read any application's history by naming its
        integration id. The trail says who granted what to whom, so that is a
        wider answer than administering one application entitles somebody to.
    */
    authorizationService.requireApplicationAccess(requester, cssIntegrationId, cssEnvironment);
    return permissionAuditService.getHistory(
        targetUserGuid, targetUserType, cssIntegrationId, cssEnvironment);
  }

  /**
   * Everyone with audit history in one application.
   *
   * <p>Where the history screen starts: an application, then the people something
   * has happened to in it, then one person's trail. Open to anyone who
   * administers that application - the same rule as reading its permissions, and
   * for the same reason: this says what has happened to access they already
   * manage.
   *
   * <p>Read from the trail rather than from CSS, so it includes people whose
   * access was since removed - who are much of the reason to open a history
   * screen at all.
   */
  @GetMapping("/users")
  @Operation(operationId = "get_permission_audit_users_by_application", summary = "People with permission audit history in an application")
  public List<PermissionAuditUserDto> getUsers(
      @RequestParam Integer cssIntegrationId,
      @RequestParam String cssEnvironment,
      Requester requester) {

    authorizationService.requireApplicationAccess(requester, cssIntegrationId, cssEnvironment);
    return permissionAuditService.getUsersWithHistory(cssIntegrationId, cssEnvironment);
  }

}
