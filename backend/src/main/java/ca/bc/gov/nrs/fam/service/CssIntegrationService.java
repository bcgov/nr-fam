package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.EmailSendingStatus;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.dto.CssApplicationOptionDto;
import ca.bc.gov.nrs.fam.dto.CssRoleDto;
import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
import ca.bc.gov.nrs.fam.dto.CssRoleOptionDto;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentRequest;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentResult;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRowDto;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.CssApiService;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Applications, roles and role assignments sourced from CSS rather than from the
 * FAM tables.
 *
 * <p>Port of {@code router_css_integration.py}.
 *
 * <p>Two costs are inherent to the CSS API and worth knowing about:
 *
 * <ul>
 *   <li>Listing roles needs one extra request per composite role, to read its
 *       children.
 *   <li>Listing assignments needs one request per role, because CSS exposes
 *       assignments only as "the users holding this role". Since scope-specific
 *       roles are created on demand and never removed, that count grows with
 *       every distinct scope value ever granted.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CssIntegrationService {

  /**
   * Above this many roles, the per-role fan-out is logged as a warning. Not a
   * limit - nothing is truncated - but the read is linear in role count and
   * roles accumulate permanently, so it is worth surfacing before it hurts.
   */
  private static final int FAN_OUT_WARN_THRESHOLD = 100;

  private final CssApiService cssApiService;
  private final PermissionAuditWriteService auditWriteService;
  private final AuthorizationService authorizationService;
  private final FamProperties famProperties;
  private final AccessGrantedEmailService accessGrantedEmailService;

  /**
   * Applications available to administer, one per integration/environment pair.
   *
   * <p>The CSS API account is team scoped, so this is every integration the team
   * owns. It says nothing about which of them a given requester may administer;
   * that is decided from the requester's own token.
   */
  public List<CssApplicationOptionDto> getApplications() {
    List<CssApplicationOptionDto> options = cssApiService.getIntegrations().stream()
        .flatMap(integration -> integration.environments().stream()
            .map(environment -> new CssApplicationOptionDto(
                integration.id(),
                environment,
                integration.projectName(),
                "%s (%s)".formatted(integration.projectName(),
                    environment.toUpperCase(java.util.Locale.ROOT)),
                integration.status())))
        .toList();

    log.debug("Returning {} application option(s) from CSS integrations.", options.size());
    return options;
  }

  /**
   * The selectable roles of one integration and environment.
   *
   * <p>A role is selectable when nothing else composes it - when it sits at the
   * top of its chain. That is what makes "Submitter (SLR)" a row while the
   * {@code FREP_EDITOR} it wraps is not, without relying on a naming convention.
   *
   * <p>Scope type is resolved by walking the whole chain for marker roles, so a
   * role is district scoped even when the marker is a grandchild rather than a
   * direct child.
   */
  public List<CssRoleOptionDto> getRoles(int integrationId, String environment) {
    List<CssRoleDto> roles = cssApiService.getRoles(integrationId, environment);

    // Children are only fetched for composite roles: GET /roles already reports
    // which roles are composite, so a leaf needs no second call.
    Map<String, List<String>> composites = new HashMap<>();
    for (CssRoleDto role : roles) {
      composites.put(role.name(), role.composite()
          ? cssApiService.getRoleComposites(integrationId, environment, role.name())
          : List.of());
    }

    // A role composed by something else is an implementation detail of that
    // something else, not a row of its own.
    Set<String> composedByOthers = composites.values().stream()
        .flatMap(List::stream).collect(java.util.stream.Collectors.toSet());

    List<CssRoleOptionDto> options = new ArrayList<>();
    for (CssRoleDto role : roles) {
      if (CssRoleNaming.MARKERS.contains(role.name()) || composedByOthers.contains(role.name())) {
        continue;
      }

      List<String> chain = descendants(role.name(), composites);

      // The machine role beneath the display role: the first descendant that is
      // not a scope marker.
      String roleCode = chain.stream()
          .filter(name -> !CssRoleNaming.MARKERS.contains(name))
          .findFirst().orElse(null);

      options.add(new CssRoleOptionDto(
          role.name(),
          role.name(),
          null,
          roleCode,
          role.composite(),
          chain,
          chain.contains(CssRoleNaming.MARKER_DISTRICT),
          chain.contains(CssRoleNaming.MARKER_FOREST_CLIENT)));
    }

    log.debug("Returning {} selectable role(s) of {} CSS role(s) for integration {} ({}).",
        options.size(), roles.size(), integrationId, environment);
    return options;
  }

  /**
   * Every descendant of a role, depth first.
   *
   * <p>{@code visited} guards against a cyclic definition: CSS does not prevent
   * one, and without the guard a cycle would recurse until the stack gives out.
   */
  private static List<String> descendants(String roleName, Map<String, List<String>> composites) {
    List<String> out = new ArrayList<>();
    collect(roleName, composites, new HashSet<>(), out);
    return out;
  }

  private static void collect(
      String roleName, Map<String, List<String>> composites,
      Set<String> visited, List<String> out) {

    if (!visited.add(roleName)) {
      return;
    }
    for (String child : composites.getOrDefault(roleName, List.of())) {
      out.add(child);
      collect(child, composites, visited, out);
    }
  }

  /**
   * Grant a role to a user, creating a scope-specific role on demand.
   *
   * <p>Unscoped, the role is assigned as-is. Scoped, one role per scope value is
   * created if absent and those are assigned instead of the base role - the scope
   * has to live in the role name because CSS roles carry no attributes and the
   * name is what reaches the token.
   *
   * <p><b>The generated role is a plain leaf.</b> It is not composed of the base
   * role, so a token carries {@code CHR_FREP_EDITOR_DISTRICT-DCC} but not
   * {@code CHR_FREP_EDITOR}. Anything authorising on the base name has to match
   * on the prefix instead.
   *
   * <p>Roles are created but never removed, so revoking a user leaves the role
   * behind.
   *
   * <p>Reports per role rather than failing whole: one district's role failing
   * should not discard the others that succeeded.
   */
  public List<CssUserRoleAssignmentResult> assignUserRoles(
      int integrationId, String environment, CssUserRoleAssignmentRequest request) {
    return assignUserRoles(integrationId, environment, request, null);
  }

  /**
   * As above, for a grant made by a person: refuses a self-grant and records the
   * result in FAM's audit trail.
   *
   * <p>The self-grant check lives here rather than in the controller, unlike the
   * other guards. It is a check on what the request contains rather than on who
   * the caller is, and putting it at the grant itself means no other caller can
   * reach {@link CssApiService} around it.
   *
   * <p>A null requester is the system path - a scheduled or internal grant with
   * nobody to self-grant to - and skips the check.
   *
   * <p>CSS keeps no history of who granted what to whom, so the audit write here
   * is the only place it is recorded. It happens after the assignment and
   * reflects what actually succeeded.
   */
  public List<CssUserRoleAssignmentResult> assignUserRoles(
      int integrationId, String environment, CssUserRoleAssignmentRequest request,
      Requester requester) {

    if (requester != null) {
      authorizationService.forbidSelfGrant(requester, request.userGuid());
    }

    String username;
    try {
      FamProperties.Integration.Css css = famProperties.integration().css();
      username = CssRoleNaming.buildUsername(request.userGuid(), request.userType(),
          css.idpAliases().idir(), css.idpAliases().bceidBusiness());
    } catch (IllegalArgumentException e) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER, e.getMessage());
    }

    List<String> targetRoles = resolveTargetRoles(request);

    Set<String> existing = new HashSet<>();
    cssApiService.getRoles(integrationId, environment)
        .forEach(role -> existing.add(role.name()));

    List<CssUserRoleAssignmentResult> results = new ArrayList<>();
    List<String> assignable = new ArrayList<>();

    for (String roleName : targetRoles) {
      try {
        boolean created = false;
        if (!existing.contains(roleName)) {
          cssApiService.createRole(integrationId, environment, roleName);
          created = true;
        }
        assignable.add(roleName);
        results.add(new CssUserRoleAssignmentResult(
            roleName, created, false, null, EmailSendingStatus.NOT_REQUIRED));
      } catch (Exception e) {
        log.warn("Could not create CSS role {}: {}", roleName, e.getMessage());
        results.add(CssUserRoleAssignmentResult.failed(roleName, e.getMessage()));
      }
    }

    if (assignable.isEmpty()) {
      return results;
    }

    // One call assigns every role that was successfully prepared, so this either
    // succeeds for all of them or fails for all of them.
    try {
      cssApiService.assignUserRoles(integrationId, environment, username, assignable);
      results.replaceAll(result -> assignable.contains(result.roleName())
          ? new CssUserRoleAssignmentResult(result.roleName(), result.roleCreated(), true, null,
              EmailSendingStatus.NOT_REQUIRED)
          : result);
    } catch (Exception e) {
      log.warn("Could not assign CSS roles to {}: {}", username, e.getMessage());
      results.replaceAll(result -> assignable.contains(result.roleName())
          ? new CssUserRoleAssignmentResult(
              result.roleName(), result.roleCreated(), false, e.getMessage(),
              EmailSendingStatus.NOT_REQUIRED)
          : result);
    }

    log.debug("CSS assignment for {}: {}", username, results);

    // Tell the user what they were given. Upstream sent this from the grant path
    // too; it reports its own outcome on each result rather than failing the
    // grant, so a mail relay being down does not look like a failed grant.
    results = accessGrantedEmailService.notifyGranted(
        request.targetUserEmail(), applicationLabel(integrationId, environment), results);

    auditWriteService.storeCssGranted(
        requester, request.userGuid(), request.userType().getCode(),
        integrationId, environment, request.roleName(), request.scopeType(), results);

    return results;
  }

  /**
   * How the application is named to a user in a notification.
   *
   * <p>The integration's project name would read better, but fetching it costs a
   * CSS round trip on every grant purely for the wording of an email.
   */
  private static String applicationLabel(int integrationId, String environment) {
    return "integration %d (%s)".formatted(
        integrationId, environment == null ? "" : environment.toUpperCase(java.util.Locale.ROOT));
  }

  private static List<String> resolveTargetRoles(CssUserRoleAssignmentRequest request) {
    if (request.scopeValues().isEmpty()) {
      return List.of(request.roleName());
    }
    if (request.scopeType() == null || request.scopeType().isBlank()) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "scope_type is required when scope_values are given.");
    }
    // Distinct: two identical scope values would otherwise create the same role
    // twice and report it twice.
    return new ArrayList<>(new LinkedHashSet<>(request.scopeValues().stream()
        .map(value -> CssRoleNaming.buildScopedRoleName(
            request.roleName(), request.scopeType(), value))
        .toList()));
  }

  /**
   * Every user/role assignment in an integration, one row per pair.
   *
   * <p>Fans out over every role - see the class note on cost. Scope is recovered
   * by parsing the role name, the only place it is recorded.
   */
  public List<CssUserRoleRowDto> getUserRoleAssignments(int integrationId, String environment) {
    List<CssRoleDto> roles = cssApiService.getRoles(integrationId, environment);

    if (roles.size() > FAN_OUT_WARN_THRESHOLD) {
      log.warn("Reading assignments for integration {} ({}) needs {} requests, one per role. "
          + "Scope-specific roles are never removed, so this grows with every scope value "
          + "ever granted.", integrationId, environment, roles.size());
    }

    List<CssUserRoleRowDto> rows = new ArrayList<>();
    for (CssRoleDto role : roles) {
      CssRoleNaming.ScopedRoleName parsed = CssRoleNaming.parse(role.name());

      for (CssApiService.CssUserDto user
          : cssApiService.getUsersWithRole(integrationId, environment, role.name())) {

        rows.add(new CssUserRoleRowDto(
            user.displayUsername(),
            CssRoleNaming.domainFromUsername(user.username()).orElse(null),
            user.firstName(),
            user.lastName(),
            user.email(),
            parsed.baseRoleName(),
            parsed.scopeType(),
            parsed.scopeValue()));
      }
    }

    log.debug("Returning {} assignment row(s) from {} role(s) for integration {} ({}).",
        rows.size(), roles.size(), integrationId, environment);
    return rows;
  }
}
