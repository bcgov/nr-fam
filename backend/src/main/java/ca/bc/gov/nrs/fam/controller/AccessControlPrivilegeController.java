package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.EmailSendingStatus;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.dto.FamAccessControlPrivilegeCreateRequest;
import ca.bc.gov.nrs.fam.dto.FamAccessControlPrivilegeCreateResponse;
import ca.bc.gov.nrs.fam.dto.FamAccessControlPrivilegeGetResponse;
import ca.bc.gov.nrs.fam.dto.FamAccessControlPrivilegeResponse;
import ca.bc.gov.nrs.fam.dto.PagedResults;
import ca.bc.gov.nrs.fam.dto.TargetUser;
import ca.bc.gov.nrs.fam.dto.UserRolePageParams;
import ca.bc.gov.nrs.fam.entity.FamAccessControlPrivilege;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.service.AccessControlPrivilegeService;
import ca.bc.gov.nrs.fam.service.AdminCsvExporter;
import ca.bc.gov.nrs.fam.service.ApiInstanceEnvResolver;
import ca.bc.gov.nrs.fam.service.RoleService;
import ca.bc.gov.nrs.fam.service.TargetUserValidationService;
import io.swagger.v3.oas.annotations.Operation;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delegated-administrator privileges.
 *
 * <p>Port of {@code router_access_control_privilege.py}. Only an application
 * admin may appoint delegated admins for that application, so the guard is
 * {@code authorizeByAppId} / {@code authorizeByApplicationRole} rather than the
 * FAM-admin check used for application admins.
 */
@Slf4j
@RestController
@RequestMapping("/access-control-privileges")
@Tag(name = "FAM Access Control Privileges")
@RequiredArgsConstructor
public class AccessControlPrivilegeController {

  private final AccessControlPrivilegeService accessControlPrivilegeService;
  private final AuthorizationService authorizationService;
  private final TargetUserValidationService targetUserValidationService;
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;
  private final RoleService roleService;
  private final AdminCsvExporter csvExporter;

  @PostMapping
  @Operation(operationId = "create_access_control_privilege_many", summary = "Grant delegated admin privileges")
  public FamAccessControlPrivilegeResponse create(
      @Valid @RequestBody FamAccessControlPrivilegeCreateRequest request, Requester requester) {

    FamRole role = requireRole(request.roleId());

    authorizationService.authorizeByApplicationRole(role, requester);

    TargetUser targetUser = TargetUser.builder()
        .userName(request.userName())
        .userGuid(request.userGuid())
        .userTypeCode(request.userTypeCode().getCode())
        .build();

    // Unconditional, unlike the end-user guard: appointing yourself a delegated
    // admin is a trust escalation, blocked even on dev/test applications.
    authorizationService.enforceSelfGrantUnconditional(requester, List.of(targetUser));

    ApiInstanceEnv apiInstanceEnv = apiInstanceEnvResolver.resolve(role.getApplication());
    TargetUser verified = targetUserValidationService.verifyUserExists(
        requester, targetUser, apiInstanceEnv);

    List<FamAccessControlPrivilegeCreateResponse> assignments =
        accessControlPrivilegeService.createMany(request, requester, verified);

    EmailSendingStatus emailStatus = request.requiresSendUserEmail()
        ? accessControlPrivilegeService.sendEmailNotification(verified, assignments)
        : EmailSendingStatus.NOT_REQUIRED;

    return new FamAccessControlPrivilegeResponse(emailStatus, assignments);
  }

  @GetMapping
  @Operation(operationId = "get_access_control_privileges_by_application_id", summary = "Delegated admin privileges for an application")
  public PagedResults<FamAccessControlPrivilegeGetResponse> getByApplication(
      @RequestParam Long applicationId,
      @ParameterObject @Valid UserRolePageParams pageParams,
      Requester requester) {

    authorizationService.authorizeByAppId(applicationId, requester);
    return accessControlPrivilegeService.getPagedByApplicationId(applicationId, pageParams);
  }

  @GetMapping(value = "/export", produces = "text/csv")
  @Operation(operationId = "export_access_control_privileges_by_application_id", summary = "Export delegated admin roles by application ID")
  public ResponseEntity<byte[]> export(
      @RequestParam Long applicationId, Requester requester) {

    authorizationService.authorizeByAppId(applicationId, requester);

    List<FamAccessControlPrivilegeGetResponse> results =
        accessControlPrivilegeService.getByApplicationId(applicationId);

    byte[] body = csvExporter.toDelegatedAdminCsv(results).getBytes(StandardCharsets.UTF_8);

    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv"))
        .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=" + csvExporter.delegatedAdminFilename(results))
        .body(body);
  }

  @DeleteMapping("/{accessControlPrivilegeId}")
  @Operation(operationId = "delete_access_control_privilege", summary = "Remove a delegated admin privilege")
  public ResponseEntity<Void> delete(
      @PathVariable Long accessControlPrivilegeId, Requester requester) {

    // The privilege is resolved first: the authorization check needs its role to
    // find the application.
    FamAccessControlPrivilege privilege =
        accessControlPrivilegeService.getById(accessControlPrivilegeId);

    authorizationService.authorizeByApplicationRole(privilege.getRole(), requester);

    TargetUser targetUser = TargetUser.builder()
        .userId(privilege.getUser().getUserId())
        .userName(privilege.getUser().getUserName())
        .userGuid(privilege.getUser().getUserGuid())
        .userTypeCode(privilege.getUser().getUserTypeCode())
        .build();

    authorizationService.enforceSelfGrantUnconditional(requester, List.of(targetUser));

    accessControlPrivilegeService.delete(requester, accessControlPrivilegeId);
    return ResponseEntity.noContent().build();
  }

  private FamRole requireRole(Long roleId) {
    FamRole role = roleId == null ? null : roleService.getRole(roleId);
    if (role == null) {
      throw FamHttpException.forbidden(ErrorCode.INVALID_ROLE_ID,
          "Role does not exist or failed to get the role_id from request.");
    }
    return role;
  }
}
