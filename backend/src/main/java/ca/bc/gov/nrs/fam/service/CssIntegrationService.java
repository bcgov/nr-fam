package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.EmailSendingStatus;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.dto.CssApplicationOptionDto;
import ca.bc.gov.nrs.fam.dto.CssRoleCreateRequest;
import ca.bc.gov.nrs.fam.dto.CssRoleDto;
import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
import ca.bc.gov.nrs.fam.dto.CssRoleOptionDto;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentRequest;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentResult;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRevokeRequest;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRowDto;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.CssApiService;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.security.TargetOrganizationGuard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  private final AssignmentVisibilityService assignmentVisibilityService;
  private final TargetOrganizationGuard targetOrganizationGuard;

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

    Map<String, String> descriptions = descriptionsFrom(roles);

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
      if (CssRoleNaming.MARKERS.contains(role.name())
          || CssRoleNaming.isLabelRole(role.name())
          || composedByOthers.contains(role.name())) {
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
          descriptions.get(role.name()),
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
   * Role code to description, read off the sidecar roles in a role listing.
   *
   * <p>Shared by the role picker and the assignment listing so the two cannot
   * describe the same role differently. Neither pays for it: both already hold
   * the full role list, and a sidecar is one of its entries.
   */
  private static Map<String, String> descriptionsFrom(List<CssRoleDto> roles) {
    Map<String, String> descriptions = new HashMap<>();
    for (CssRoleDto role : roles) {
      CssRoleNaming.parseLabel(role.name())
          .ifPresent(label -> descriptions.put(label.roleCode(), label.description()));
    }
    return descriptions;
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
   * Define a new role on an integration.
   *
   * <p>Three CSS roles can be involved, because a CSS role holds nothing but a
   * name:
   *
   * <pre>
   * FREP_ADMINISTRATOR                                  the role; its name is the code
   * └── HAS_DISTRICT_ROLE                               composite child, when scoped
   * FAM:LABEL:FREP_ADMINISTRATOR:FREP Administrator     sidecar, holds the description
   * </pre>
   *
   * <p>The scope marker is created if the integration has never used one. It is
   * attached as a composite child, which is how {@link #getRoles} recognises the
   * scope - and how roles configured by hand before this screen existed were
   * already shaped, so both are read the same way.
   *
   * <p><b>Cleaned up on failure.</b> Half a role - one with no description, or a
   * scoped role missing its marker - would be indistinguishable from one somebody
   * meant to create, and there is no way to finish it from the screen because the
   * code is already taken. So anything created here is removed again if a later
   * step fails. Only roles this call created are removed: a marker that already
   * existed belongs to other roles, and deleting it would silently unscope them.
   *
   * @return the new role as the picker will see it
   */
  public CssRoleOptionDto createRole(
      int integrationId, String environment, CssRoleCreateRequest request, Requester requester) {

    String roleCode = request.roleCode().trim().toUpperCase(java.util.Locale.ROOT);
    String description = request.description().trim();

    String scopeType;
    try {
      scopeType = request.scopeType();
    } catch (IllegalArgumentException e) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER, e.getMessage());
    }

    if (!CssRoleNaming.isValidRoleCode(roleCode)) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "A role code must start with a letter and contain only letters, digits and "
              + "underscores, e.g. FREP_ADMINISTRATOR.");
    }
    if (CssRoleNaming.MARKERS.contains(roleCode)) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "%s is reserved: it is how FAM marks a role as scoped.".formatted(roleCode));
    }

    Set<String> existing = cssApiService.getRoles(integrationId, environment).stream()
        .map(CssRoleDto::name).collect(java.util.stream.Collectors.toCollection(HashSet::new));

    if (existing.contains(roleCode)) {
      // Refused rather than treated as an edit. The role may already be granted
      // to people, and silently redefining what they hold is not what "create"
      // should do.
      throw FamHttpException.conflict(ErrorCode.INVALID_OPERATION,
          "A role named %s already exists in this application.".formatted(roleCode));
    }

    String labelRole = CssRoleNaming.buildLabelRoleName(roleCode, description);
    List<String> created = new ArrayList<>();

    try {
      cssApiService.createRole(integrationId, environment, roleCode);
      created.add(roleCode);

      Optional<String> marker = CssRoleNaming.markerFor(scopeType);
      if (marker.isPresent()) {
        if (!existing.contains(marker.get())
            && cssApiService.createRole(integrationId, environment, marker.get())) {
          created.add(marker.get());
        }
        cssApiService.addRoleComposites(
            integrationId, environment, roleCode, List.of(marker.get()));
      }

      if (!existing.contains(labelRole)
          && cssApiService.createRole(integrationId, environment, labelRole)) {
        created.add(labelRole);
      }

    } catch (RuntimeException e) {
      rollback(integrationId, environment, created);
      throw e;
    }

    log.info("Created CSS role {} ({}) on integration {} ({}), scope {}.",
        roleCode, description, integrationId, environment,
        scopeType == null ? "none" : scopeType);

    // After the role exists, and reflecting what was actually created. CSS keeps
    // no history of role definitions, so this row is the only record of who
    // introduced it. A failure here is not swallowed - see storeRoleCreated.
    auditWriteService.storeRoleCreated(
        requester, integrationId, environment, roleCode, description, scopeType);

    return new CssRoleOptionDto(
        roleCode, roleCode, description, null,
        scopeType != null,
        CssRoleNaming.markerFor(scopeType).map(List::of).orElseGet(List::of),
        "DISTRICT".equals(scopeType),
        "FOREST_CLIENT".equals(scopeType));
  }

  /**
   * Undo what a failed creation had managed to create.
   *
   * <p>Best effort by necessity: it runs because something already went wrong
   * upstream, so it cannot assume the next call will work either. A failure to
   * clean up is logged rather than thrown, so the caller still sees the error that
   * actually caused this.
   */
  private void rollback(int integrationId, String environment, List<String> roleNames) {
    for (String roleName : roleNames) {
      try {
        cssApiService.deleteRole(integrationId, environment, roleName);
      } catch (RuntimeException e) {
        log.warn("Could not remove {} after a failed role creation on integration {} ({}): {}. "
            + "It may need removing by hand in the CSS console.",
            roleName, integrationId, environment, e.getMessage());
      }
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
   * <p><b>The user is created in Keycloak if they are not there yet</b>, which is
   * what makes granting access to a new starter possible at all - see
   * {@link CssApiService#assignUserRoles}. Their record carries only a username
   * until they first sign in, so they appear in the assignment listing without a
   * name or email until then.
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

    // A Business BCeID administrator may only grant within their own
    // organisation. Checked here rather than in the controller for the same
    // reason as the self-grant rule: it is a check on who the request names, and
    // at the grant itself no other caller can reach CSS around it.
    targetOrganizationGuard.requireSameOrganization(
        requester, request.userType(), request.userGuid());

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
   * Take a role away from a user.
   *
   * <p>Guarded exactly as granting is, and for the same reasons: a requester may
   * not alter their own access, and a Business BCeID administrator may only act
   * on their own organisation. Removing access is not the safer direction - it
   * locks people out - so it is not the looser one either.
   *
   * <p><b>The role is not deleted</b>, only the assignment. The role stays
   * defined on the integration so it can be granted again, which is also why
   * scope-specific roles accumulate.
   *
   * <p>CSS keeps no history of what it removed, so the audit record written here
   * is the only trace that the user ever held it.
   */
  public void revokeUserRole(
      int integrationId, String environment, CssUserRoleRevokeRequest request,
      Requester requester) {

    if (requester != null) {
      authorizationService.forbidSelfGrant(requester, request.userGuid());
    }
    targetOrganizationGuard.requireSameOrganization(
        requester, request.userType(), request.userGuid());

    String username;
    try {
      FamProperties.Integration.Css css = famProperties.integration().css();
      username = CssRoleNaming.buildUsername(request.userGuid(), request.userType(),
          css.idpAliases().idir(), css.idpAliases().bceidBusiness());
    } catch (IllegalArgumentException e) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER, e.getMessage());
    }

    // The concrete role CSS holds, which the listing split into a base role and
    // a scope to display it.
    String cssRoleName = request.scopeValue() == null || request.scopeValue().isBlank()
        ? request.roleName()
        : CssRoleNaming.buildScopedRoleName(
            request.roleName(), request.scopeType(), request.scopeValue());

    cssApiService.removeUserRole(integrationId, environment, username, cssRoleName);

    log.info("Revoked {} from {} on integration {} ({}).",
        cssRoleName, username, integrationId, environment);

    // After the removal and only for what was actually removed.
    auditWriteService.storeCssRevoked(
        requester, request.userGuid(), request.userType().getCode(),
        integrationId, environment, request.roleName(), request.scopeType(),
        List.of(cssRoleName));
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
  public List<CssUserRoleRowDto> getUserRoleAssignments(
      int integrationId, String environment, Requester requester) {
    List<CssRoleDto> roles = cssApiService.getRoles(integrationId, environment);

    if (roles.size() > FAN_OUT_WARN_THRESHOLD) {
      log.warn("Reading assignments for integration {} ({}) needs {} requests, one per role. "
          + "Scope-specific roles are never removed, so this grows with every scope value "
          + "ever granted.", integrationId, environment, roles.size());
    }

    Map<String, String> descriptions = descriptionsFrom(roles);

    List<CssUserRoleRowDto> rows = new ArrayList<>();
    for (CssRoleDto role : roles) {
      // A sidecar holds a description and is granted to nobody. Skipping it saves
      // a request, and means a row could never appear for one if somebody
      // assigned it by hand in the CSS console.
      if (CssRoleNaming.isLabelRole(role.name())) {
        continue;
      }

      CssRoleNaming.ScopedRoleName parsed = CssRoleNaming.parse(role.name());

      for (CssApiService.CssUserDto user
          : cssApiService.getUsersWithRole(integrationId, environment, role.name())) {

        rows.add(new CssUserRoleRowDto(
            user.displayUsername(),
            CssRoleNaming.guidFromUsername(user.username()).orElse(null),
            CssRoleNaming.domainFromUsername(user.username()).orElse(null),
            user.firstName(),
            user.lastName(),
            user.email(),
            parsed.baseRoleName(),
            descriptions.get(parsed.baseRoleName()),
            parsed.scopeType(),
            parsed.scopeValue()));
      }
    }

    log.debug("Returning {} assignment row(s) from {} role(s) for integration {} ({}).",
        rows.size(), roles.size(), integrationId, environment);

    // Two things happen here, both requester-dependent: a Business BCeID
    // administrator sees only their own organisation's BCeID users, and rows CSS
    // could not name are resolved against the directory.
    return assignmentVisibilityService.visibleTo(requester, rows);
  }
}
