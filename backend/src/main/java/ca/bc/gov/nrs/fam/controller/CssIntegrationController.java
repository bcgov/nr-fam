package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.dto.CssApplicationOptionDto;
import ca.bc.gov.nrs.fam.dto.CssRoleOptionDto;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentRequest;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentResult;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
   * Every user/role assignment in an integration.
   *
   * <p>Costs one request per role upstream; see {@link CssIntegrationService}.
   */
  @GetMapping("/{integrationId}/{environment}/user-role-assignments")
  @Operation(operationId = "get_css_user_role_assignments",
      summary = "List every user/role assignment in a CSS integration environment")
  public List<CssUserRoleRowDto> getCssUserRoleAssignments(
      @PathVariable int integrationId,
      @PathVariable String environment,
      Requester requester) {

    authorizationService.requireApplicationAccess(requester, integrationId, environment);
    return cssIntegrationService.getUserRoleAssignments(integrationId, environment);
  }
}
