package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import ca.bc.gov.nrs.fam.dto.CssAdministratorAppointRequest;
import ca.bc.gov.nrs.fam.dto.CssAdministratorRowDto;
import ca.bc.gov.nrs.fam.dto.CssApplicationOptionDto;
import ca.bc.gov.nrs.fam.dto.CssDelegatedAdminRequest;
import ca.bc.gov.nrs.fam.dto.CssRoleBulkCreateResultDto;
import ca.bc.gov.nrs.fam.dto.CssRoleCreateRequest;
import ca.bc.gov.nrs.fam.dto.CssRoleDeleteResultDto;
import ca.bc.gov.nrs.fam.dto.CssRoleMemberCountDto;
import ca.bc.gov.nrs.fam.dto.CssRoleOptionDto;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentRequest;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentResult;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRevokeRequest;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRowDto;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.service.CssIntegrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Port of {@code router_css_integration.py}.
 *
 * <p>An application is identified by the pair {@code (integrationId,
 * environment)} rather than by a single id, because a CSS integration spans
 * environments while a FAM application does not.
 */
@Validated
@RestController
@RequestMapping("/css-applications")
@Tag(name = "CSS Integrations")
@RequiredArgsConstructor
public class CssIntegrationController {

  private final CssIntegrationService cssIntegrationService;
  private final AuthorizationService authorizationService;

  /**
   * Applications available to administer, sourced from CSS integrations.
   *
   * <p>Filtered to the applications this requester administers, using the same
   * predicate the per-request guards apply - so the picker never offers something
   * the next call would refuse, and never hides something it would allow.
   *
   * <p>FAM's own integration is included only for {@code FAM_ADMIN}. The
   * filtering happens here rather than in the browser: the response must not
   * carry an application the caller may not administer.
   */
  @GetMapping
  @Operation(operationId = "get_css_applications",
      summary = "List applications sourced from CSS integrations")
  public List<CssApplicationOptionDto> getCssApplications(Requester requester) {
    authorizationService.authorize(requester);

    // The CSS API account is team scoped and returns every integration the team
    // owns. Filtering to what this requester actually administers is what stops
    // the picker offering applications they cannot act on.
    return cssIntegrationService.getApplications().stream()
        .filter(app -> authorizationService.canAdminister(
            requester, app.integrationId(), app.environment()))
        .toList();
  }

  /** Selectable roles for one CSS integration and environment. */
  @GetMapping("/{integrationId}/{environment}/roles")
  @Operation(operationId = "get_css_application_roles",
      summary = "List the selectable roles of a CSS integration environment")
  public List<CssRoleOptionDto> getCssApplicationRoles(
      @PathVariable int integrationId,
      @PathVariable String environment,
      Requester requester) {

    authorizationService.requireApplicationAccess(requester, integrationId, environment);
    return cssIntegrationService.getRoles(integrationId, environment);
  }

  /**
   * Define a new role on an application.
   *
   * <p><b>FAM administrators only</b>, and deliberately stricter than the rest of
   * this controller. Everything else here decides who holds an existing role;
   * this decides what roles exist at all, which is a change to the application's
   * own authorisation model rather than to one person's access. An application
   * administrator can hand out what the application already defines without also
   * being able to invent something new for it to mean.
   *
   * <p>Not restricted per application: a FAM administrator administers every one.
   */
  @PostMapping("/{integrationId}/{environment}/roles")
  @Operation(operationId = "create_css_application_role",
      summary = "Define a new role on a CSS integration environment")
  public CssRoleOptionDto createCssApplicationRole(
      @PathVariable int integrationId,
      @PathVariable String environment,
      @Valid @RequestBody CssRoleCreateRequest request,
      Requester requester) {

    authorizationService.authorizeByFamAdmin(requester);
    return cssIntegrationService.createRole(integrationId, environment, request, requester);
  }

  /**
   * Define the same role in every environment the application has.
   *
   * <p>No {@code environment} in the path, because that is the point: the role
   * is created in all of them. Refuses outright if the code is taken in any one,
   * rather than creating what fits - see
   * {@link CssIntegrationService#createRoleInAllEnvironments}.
   *
   * <p><b>FAM administrators only</b>, as with creating a role in one
   * environment.
   */
  @PostMapping("/{integrationId}/roles")
  @Operation(operationId = "create_css_application_role_all_environments",
      summary = "Define a role in every environment of a CSS integration")
  public CssRoleBulkCreateResultDto createCssApplicationRoleInAllEnvironments(
      @PathVariable int integrationId,
      @Valid @RequestBody CssRoleCreateRequest request,
      Requester requester) {

    authorizationService.authorizeByFamAdmin(requester);
    return cssIntegrationService.createRoleInAllEnvironments(integrationId, request, requester);
  }

  /**
   * Remove a role from an application.
   *
   * <p><b>FAM administrators only</b>, for the same reason as creating one, and
   * more so: this removes what the application defines, and takes the role away
   * from everyone who held it in the same act.
   */
  @DeleteMapping("/{integrationId}/{environment}/roles/{roleName}")
  @Operation(operationId = "delete_css_application_role",
      summary = "Remove a role, its sidecar and any roles derived from it")
  public CssRoleDeleteResultDto deleteCssApplicationRole(
      @PathVariable int integrationId,
      @PathVariable String environment,
      @PathVariable String roleName,
      Requester requester) {

    authorizationService.authorizeByFamAdmin(requester);
    return cssIntegrationService.deleteRole(integrationId, environment, roleName, requester);
  }

  /**
   * The people administering an application, at one tier.
   *
   * <p>Backs the Delegated admins and Application admins tabs.
   *
   * <p><b>Application administrators and above.</b> A delegated administrator is
   * excluded deliberately, matching the tiers that may appoint administrators at
   * all - knowing who else administers an application is part of administering
   * it, not part of granting ordinary access.
   */
  @GetMapping("/{integrationId}/{environment}/administrators")
  @Operation(operationId = "get_css_application_administrators",
      summary = "List the administrators of an application at one tier")
  public List<CssAdministratorRowDto> getCssApplicationAdministrators(
      @PathVariable int integrationId,
      @PathVariable String environment,
      @RequestParam AdminRoleAuthGroup tier,
      Requester requester) {

    authorizationService.requireDelegatedAdminManagement(requester, integrationId, environment);
    return cssIntegrationService.getAdministrators(integrationId, environment, tier);
  }

  /**
   * Appoint a delegated administrator for one role.
   *
   * <p>Returns one result per delegation, because a scoped appointment is one
   * delegation per scope value and any of them can fail on its own.
   *
   * <p><b>Application administrators and above</b>, and never oneself - the
   * guards are applied in the service, alongside the same-organisation rule the
   * grant path uses.
   */
  @PostMapping("/{integrationId}/{environment}/delegated-admins")
  @Operation(operationId = "create_css_delegated_admin",
      summary = "Appoint a delegated administrator for one role")
  public List<CssUserRoleAssignmentResult> createCssDelegatedAdmin(
      @PathVariable int integrationId,
      @PathVariable String environment,
      @Valid @RequestBody CssDelegatedAdminRequest request,
      Requester requester) {

    return cssIntegrationService.appointDelegatedAdmin(
        integrationId, environment, request, requester);
  }

  /**
   * Appoint an application administrator.
   *
   * <p>No role in the body: an application administrator is authorised over the
   * application, not over one of its roles.
   */
  @PostMapping("/{integrationId}/{environment}/application-admins")
  @Operation(operationId = "create_css_application_admin",
      summary = "Appoint an application administrator")
  public CssUserRoleAssignmentResult createCssApplicationAdmin(
      @PathVariable int integrationId,
      @PathVariable String environment,
      @Valid @RequestBody CssAdministratorAppointRequest request,
      Requester requester) {

    return cssIntegrationService.appointApplicationAdmin(
        integrationId, environment, request, requester);
  }

  /** Remove somebody's application administrator role. */
  @DeleteMapping("/{integrationId}/{environment}/application-admins")
  @Operation(operationId = "delete_css_application_admin",
      summary = "Remove an application administrator")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteCssApplicationAdmin(
      @PathVariable int integrationId,
      @PathVariable String environment,
      @Valid @RequestBody CssAdministratorAppointRequest request,
      Requester requester) {

    cssIntegrationService.removeApplicationAdmin(
        integrationId, environment, request, requester);
  }

  /**
   * Withdraw a delegation.
   *
   * <p>Takes the same body as the appointment: a delegation is identified by the
   * role and scope it covers, not by an id, because nothing here has one.
   */
  @DeleteMapping("/{integrationId}/{environment}/delegated-admins")
  @Operation(operationId = "delete_css_delegated_admin",
      summary = "Withdraw a delegated administrator's delegation for one role")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteCssDelegatedAdmin(
      @PathVariable int integrationId,
      @PathVariable String environment,
      @Valid @RequestBody CssDelegatedAdminRequest request,
      Requester requester) {

    cssIntegrationService.removeDelegatedAdmin(integrationId, environment, request, requester);
  }

  /**
   * How many people hold each role of an application.
   *
   * <p>Separate from the role listing because it costs one upstream request per
   * role. The grant screen's picker reads the listing and should not pay for
   * counts it does not show.
   *
   * <p>Readable by anyone who may administer the application: it says how many
   * people hold a role, not who they are, so it reveals nothing the assignment
   * listing does not already show that caller.
   */
  @GetMapping("/{integrationId}/{environment}/roles/member-counts")
  @Operation(operationId = "get_css_application_role_member_counts",
      summary = "Count the people holding each role of a CSS integration environment")
  public List<CssRoleMemberCountDto> getCssApplicationRoleMemberCounts(
      @PathVariable int integrationId,
      @PathVariable String environment,
      Requester requester) {

    authorizationService.requireApplicationAccess(requester, integrationId, environment);
    return cssIntegrationService.getRoleMemberCounts(integrationId, environment);
  }

  /**
   * Grant a role to a user, creating a scope-specific role on demand.
   *
   * <p>Returns one result per role. A scoped grant can partly succeed, so the
   * response is a list of outcomes rather than a single status.
   *
   * <p>Any of the three administrative tiers may grant ordinary application
   * roles. Granting one of FAM's own administrative roles - appointing an
   * application or delegated administrator - additionally requires the
   * appointing tier.
   */
  @PostMapping("/{integrationId}/{environment}/user-role-assignments")
  @Operation(operationId = "create_css_user_role_assignment",
      summary = "Grant a role, creating a scope-specific role if needed")
  public List<CssUserRoleAssignmentResult> createCssUserRoleAssignment(
      @PathVariable int integrationId,
      @PathVariable String environment,
      @Valid @RequestBody CssUserRoleAssignmentRequest request,
      Requester requester) {

    authorizationService.requireApplicationAccess(requester, integrationId, environment);

    // Granting one of FAM's own administrative roles is appointing an
    // administrator, not granting application access, so it needs the stricter
    // rule. Without this a delegated admin could grant themselves
    // DELEGATED_ADMIN_<other app>_<env> - or APP_ADMIN - through the ordinary
    // grant path, and the tiers would mean nothing.
    FamAdminRole.tierOf(request.roleName()).ifPresent(tier ->
        authorizationService.requireDelegatedAdminManagement(
            requester, integrationId, environment));

    return cssIntegrationService.assignUserRoles(
        integrationId, environment, request, requester);
  }

  /**
   * Take a role away from a user.
   *
   * <p>Same tier rules as granting, including the stricter one for FAM's own
   * administrative roles: removing somebody's {@code APP_ADMIN} is as much an
   * act of administration as granting it.
   */
  @DeleteMapping("/{integrationId}/{environment}/user-role-assignments")
  @Operation(operationId = "delete_css_user_role_assignment",
      summary = "Revoke a role from a user")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteCssUserRoleAssignment(
      @PathVariable int integrationId,
      @PathVariable String environment,
      @Valid @RequestBody CssUserRoleRevokeRequest request,
      Requester requester) {

    authorizationService.requireApplicationAccess(requester, integrationId, environment);

    FamAdminRole.tierOf(request.roleName()).ifPresent(tier ->
        authorizationService.requireDelegatedAdminManagement(
            requester, integrationId, environment));

    cssIntegrationService.revokeUserRole(integrationId, environment, request, requester);
  }

  /**
   * Every user/role assignment in an integration.
   *
   * <p>Costs one request per role upstream; see {@link CssIntegrationService}.
   *
   * <p>Filtered to what this requester may see: a Business BCeID administrator
   * gets only their own organisation's BCeID users. That filtering happens in the
   * service rather than the browser - the response must not carry users the
   * caller may not see.
   */
  @GetMapping("/{integrationId}/{environment}/user-role-assignments")
  @Operation(operationId = "get_css_user_role_assignments",
      summary = "List every user/role assignment in a CSS integration environment")
  public List<CssUserRoleRowDto> getCssUserRoleAssignments(
      @PathVariable int integrationId,
      @PathVariable String environment,
      Requester requester) {

    authorizationService.requireApplicationAccess(requester, integrationId, environment);
    return cssIntegrationService.getUserRoleAssignments(integrationId, environment, requester);
  }
}
