package ca.bc.gov.nrs.fam.controller;

import org.springdoc.core.annotations.ParameterObject;
import ca.bc.gov.nrs.fam.dto.FamApplicationUserRoleAssignmentGetDto;
import ca.bc.gov.nrs.fam.dto.FamUserInfoDto;
import ca.bc.gov.nrs.fam.dto.PagedResults;
import ca.bc.gov.nrs.fam.dto.UserRolePageParams;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.service.ApplicationService;
import ca.bc.gov.nrs.fam.service.UserRoleCsvExporter;
import ca.bc.gov.nrs.fam.service.UserService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Port of {@code router_application.py}. */
@Slf4j
@RestController
@RequestMapping("/fam-applications")
@Tag(name = "FAM Applications")
@RequiredArgsConstructor
public class ApplicationController {

  private final ApplicationService applicationService;
  private final UserService userService;
  private final AuthorizationService authorizationService;
  private final UserRoleCsvExporter csvExporter;

  @GetMapping("/{applicationId}/user-role-assignment")
  @Operation(operationId = "get_fam_application_user_role_assignment", summary = "Paged users and roles assigned within an application")
  public PagedResults<FamApplicationUserRoleAssignmentGetDto> getUserRoleAssignments(
      @PathVariable Long applicationId,
      @ParameterObject @Valid UserRolePageParams pageParams,
      Requester requester) {

    authorizationService.authorizeByAppId(applicationId, requester);
    authorizationService.enforceBceidTermsConditions(requester);

    return applicationService.getApplicationRoleAssignments(applicationId, requester, pageParams);
  }

  /**
   * CSV export of the same listing.
   *
   * <p>Built in memory rather than streamed: the response is bounded by one
   * application's assignments, and a materialised body lets the error handler
   * still return JSON if the query fails. {@code Access-Control-Expose-Headers}
   * is required for the browser to read the filename from
   * {@code Content-Disposition}.
   */
  @GetMapping(value = "/{applicationId}/user-role-assignment/export", produces = "text/csv")
  @Operation(operationId = "export_application_user_roles", summary = "Export user roles information by application ID")
  public ResponseEntity<byte[]> exportUserRoles(
      @PathVariable Long applicationId, Requester requester) {

    authorizationService.authorizeByAppId(applicationId, requester);
    authorizationService.enforceBceidTermsConditions(requester);

    List<FamApplicationUserRoleAssignmentGetDto> results =
        applicationService.getApplicationRoleAssignmentsNoPaging(applicationId, requester);

    byte[] body = csvExporter.toCsv(results).getBytes(StandardCharsets.UTF_8);

    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv"))
        .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=" + csvExporter.buildFilename(results))
        .body(body);
  }

  @GetMapping("/{applicationId}/users/{userId}")
  @Operation(operationId = "get_application_user_by_id", summary = "Retrieve user information by user ID under an application")
  public FamUserInfoDto getApplicationUser(
      @PathVariable Long applicationId, @PathVariable Long userId, Requester requester) {

    authorizationService.authorizeByAppId(applicationId, requester);
    return userService.getUserInfo(userId);
  }
}
