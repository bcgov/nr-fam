package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.dto.PermissionAuditHistoryDto;
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
      @RequestParam Long userId, @RequestParam Long applicationId, Requester requester) {

    authorizationService.authorizeByAppId(applicationId, requester);
    return permissionAuditService.getHistory(userId, applicationId);
  }
}
