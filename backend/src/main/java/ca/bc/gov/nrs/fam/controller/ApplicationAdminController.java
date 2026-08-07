package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.dto.FamAppAdminCreateRequest;
import ca.bc.gov.nrs.fam.dto.FamAppAdminGetResponse;
import ca.bc.gov.nrs.fam.dto.TargetUser;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.entity.FamApplicationAdmin;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.service.AdminCsvExporter;
import ca.bc.gov.nrs.fam.service.ApiInstanceEnvResolver;
import ca.bc.gov.nrs.fam.service.ApplicationAdminService;
import ca.bc.gov.nrs.fam.service.ApplicationService;
import ca.bc.gov.nrs.fam.service.TargetUserValidationService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Port of {@code router_application_admin.py}.
 *
 * <p>Managing who administers an application is FAM-wide authority, so almost
 * everything here requires a FAM admin. The one exception is the per-application
 * listing, which an admin of that application may read.
 */
@Slf4j
@RestController
@RequestMapping("/application-admins")
@Tag(name = "FAM Application Admin")
@RequiredArgsConstructor
public class ApplicationAdminController {

  private final ApplicationAdminService applicationAdminService;
  private final ApplicationService applicationService;
  private final AuthorizationService authorizationService;
  private final TargetUserValidationService targetUserValidationService;
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;
  private final AdminCsvExporter csvExporter;

  @GetMapping
  @Operation(operationId = "get_application_admins", summary = "All application admins")
  public List<FamAppAdminGetResponse> getApplicationAdmins(Requester requester) {
    authorizationService.authorizeByFamAdmin(requester);
    return applicationAdminService.getApplicationAdmins();
  }

  /**
   * Administrators of one application, excluding the caller.
   *
   * <p>Readable by an admin of that application, not only a FAM admin - it is how
   * they see their peers.
   */
  @GetMapping("/application/{applicationId}")
  @Operation(operationId = "get_application_admins_by_application_id", summary = "Application admins for one application")
  public List<FamAppAdminGetResponse> getByApplication(
      @PathVariable Long applicationId, Requester requester) {

    authorizationService.authorizeByAppId(applicationId, requester);
    return applicationAdminService.getApplicationAdminsByApplication(
        applicationId, requester.userId());
  }

  @GetMapping(value = "/export", produces = "text/csv")
  @Operation(operationId = "export_application_admins", summary = "Export application admins information")
  public ResponseEntity<byte[]> export(Requester requester) {
    authorizationService.authorizeByFamAdmin(requester);

    List<FamAppAdminGetResponse> results = applicationAdminService.getApplicationAdmins();
    byte[] body = csvExporter.toApplicationAdminCsv(results).getBytes(StandardCharsets.UTF_8);

    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv"))
        .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=" + csvExporter.applicationAdminFilename())
        .body(body);
  }

  @PostMapping
  @Operation(operationId = "create_application_admin", summary = "Make a user an administrator of an application")
  public FamAppAdminGetResponse createApplicationAdmin(
      @Valid @RequestBody FamAppAdminCreateRequest request, Requester requester) {

    authorizationService.authorizeByFamAdmin(requester);

    FamApplication application = applicationService.requireApplication(request.applicationId());

    TargetUser targetUser = TargetUser.builder()
        .userName(request.userName())
        .userGuid(request.userGuid())
        .userTypeCode(request.userTypeCode().getCode())
        .build();

    // Unconditional: granting admin authority to yourself is never allowed, not
    // even on a dev/test application.
    authorizationService.enforceSelfGrantUnconditional(requester, List.of(targetUser));

    // Verify the identity against IDIM before creating anything for it.
    ApiInstanceEnv apiInstanceEnv = apiInstanceEnvResolver.resolve(application);
    TargetUser verified = targetUserValidationService.verifyUserExists(
        requester, targetUser, apiInstanceEnv);

    return applicationAdminService.createApplicationAdmin(request, verified, requester);
  }

  @DeleteMapping("/{applicationAdminId}")
  @Operation(operationId = "delete_application_admin", summary = "Remove an application administrator")
  public ResponseEntity<Void> deleteApplicationAdmin(
      @PathVariable Long applicationAdminId, Requester requester) {

    authorizationService.authorizeByFamAdmin(requester);

    FamApplicationAdmin admin = applicationAdminService.getById(applicationAdminId);

    TargetUser targetUser = TargetUser.builder()
        .userId(admin.getUser().getUserId())
        .userName(admin.getUser().getUserName())
        .userGuid(admin.getUser().getUserGuid())
        .userTypeCode(admin.getUser().getUserTypeCode())
        .build();

    authorizationService.enforceSelfGrantUnconditional(requester, List.of(targetUser));

    applicationAdminService.deleteApplicationAdmin(requester, applicationAdminId);
    return ResponseEntity.noContent().build();
  }
}
