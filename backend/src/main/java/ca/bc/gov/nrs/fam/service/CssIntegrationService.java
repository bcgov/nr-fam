package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.EmailSendingStatus;
import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.dto.CssAdministratorAppointRequest;
import ca.bc.gov.nrs.fam.dto.CssAdministratorRowDto;
import ca.bc.gov.nrs.fam.dto.CssApplicationOptionDto;
import ca.bc.gov.nrs.fam.dto.CssDelegatedAdminRequest;
import ca.bc.gov.nrs.fam.dto.CssIntegrationDto;
import ca.bc.gov.nrs.fam.dto.CssRoleBulkCreateResultDto;
import ca.bc.gov.nrs.fam.dto.CssRoleCreateRequest;
import ca.bc.gov.nrs.fam.dto.CssRoleDeleteResultDto;
import ca.bc.gov.nrs.fam.dto.CssRoleDto;
import ca.bc.gov.nrs.fam.dto.CssRoleMemberCountDto;
import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
import ca.bc.gov.nrs.fam.dto.CssRoleOptionDto;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentRequest;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentResult;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRevokeRequest;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRowDto;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import ca.bc.gov.nrs.fam.integration.CssApiService;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.security.TargetOrganizationGuard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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

    Map<String, String> displayNames = sidecarText(roles, CssRoleNaming::parseLabel);
    Map<String, String> descriptions = sidecarText(roles, CssRoleNaming::parseDescription);

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
          || CssRoleNaming.isSidecarRole(role.name())
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
          displayNames.get(role.name()),
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
   * Role code to sidecar text, read off a role listing.
   *
   * <p>Takes the parser so the same walk serves both sidecars - display names
   * ({@code FAM:LABEL}) and descriptions ({@code FAM:DESC}). Shared by the role
   * picker and the assignment listing so the two cannot name the same role
   * differently. Neither pays for it: both already hold the full role list, and
   * the sidecars are entries in it.
   */
  private static Map<String, String> sidecarText(
      List<CssRoleDto> roles,
      java.util.function.Function<String, Optional<CssRoleNaming.RoleLabel>> parser) {

    Map<String, String> text = new HashMap<>();
    for (CssRoleDto role : roles) {
      parser.apply(role.name())
          .ifPresent(label -> text.put(label.roleCode(), label.text()));
    }
    return text;
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

    RoleDefinition definition = validate(request);

    requireRoleAbsent(integrationId, environment, definition.roleCode());
    createArtifacts(integrationId, environment, definition);

    // After the role exists, and reflecting what was actually created. CSS keeps
    // no history of role definitions, so this row is the only record of who
    // introduced it. A failure here is not swallowed - see storeRoleCreated.
    auditWriteService.storeRoleCreated(requester, integrationId, environment,
        definition.roleCode(), definition.displayName(), definition.description(),
        definition.scopeType());

    return definition.asOption();
  }

  /**
   * Define the same role in every environment the integration has.
   *
   * <p>A role is a per-environment thing in CSS, so an application that runs in
   * dev, test and prod needs it defined three times. Doing that a screen at a
   * time invites the environments to drift - a typo in one, or a description
   * that does not match.
   *
   * <p><b>Every environment is checked before any is written.</b> Creating what
   * fits and reporting the rest would leave the code taken in some environments
   * and free in others, which cannot be corrected from this screen: creating
   * again fails on the environments that already have it. So a code in use
   * anywhere refuses the whole request and nothing is written.
   *
   * <p>Should a later environment fail anyway - upstream trouble rather than a
   * name clash - the environments already written are rolled back, for the same
   * reason. Nobody can hold a role created seconds ago, so removing it takes no
   * access away. The audit is written only once every environment has succeeded,
   * so it never records a creation that was undone.
   *
   * <p>The environment list comes from the integration rather than being assumed
   * to be dev/test/prod: an integration with only dev and test gets two, and
   * asking CSS for an environment it does not have would fail the request.
   */
  public CssRoleBulkCreateResultDto createRoleInAllEnvironments(
      int integrationId, CssRoleCreateRequest request, Requester requester) {

    RoleDefinition definition = validate(request);

    List<String> environments = cssApiService.getIntegrations().stream()
        .filter(integration -> integration.id() != null && integration.id() == integrationId)
        .findFirst()
        .map(CssIntegrationDto::environments)
        .orElseThrow(() -> FamHttpException.notFound(ErrorCode.INVALID_APPLICATION_ID,
            "No CSS integration %d.".formatted(integrationId)));

    if (environments.isEmpty()) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_OPERATION,
          "Integration %d has no environments to create the role in.".formatted(integrationId));
    }

    List<String> taken = environments.stream()
        .filter(environment -> roleExists(integrationId, environment, definition.roleCode()))
        .toList();

    if (!taken.isEmpty()) {
      throw FamHttpException.conflict(ErrorCode.INVALID_OPERATION,
          "A role named %s already exists in %s. Nothing was created."
              .formatted(definition.roleCode(), String.join(", ", taken)));
    }

    Map<String, List<String>> createdByEnvironment = new LinkedHashMap<>();
    try {
      for (String environment : environments) {
        createdByEnvironment.put(
            environment, createArtifacts(integrationId, environment, definition));
      }
    } catch (RuntimeException e) {
      createdByEnvironment.forEach(
          (environment, names) -> rollback(integrationId, environment, names));
      throw e;
    }

    // Only now, so a rolled-back attempt leaves no audit claiming it happened.
    for (String environment : environments) {
      auditWriteService.storeRoleCreated(requester, integrationId, environment,
          definition.roleCode(), definition.displayName(), definition.description(),
          definition.scopeType());
    }

    log.info("Created CSS role {} ({}) on integration {} in {}, scope {}.",
        definition.roleCode(), definition.displayName(), integrationId,
        String.join(", ", environments),
        definition.scopeType() == null ? "none" : definition.scopeType());

    return new CssRoleBulkCreateResultDto(
        definition.roleCode(), definition.displayName(), environments, definition.asOption());
  }

  /** A validated, normalised role definition. */
  private record RoleDefinition(
      String roleCode, String displayName, String description, String scopeType) {

    CssRoleOptionDto asOption() {
      return new CssRoleOptionDto(
          roleCode, displayName, description, null,
          scopeType != null,
          CssRoleNaming.markerFor(scopeType).map(List::of).orElseGet(List::of),
          "DISTRICT".equals(scopeType),
          "FOREST_CLIENT".equals(scopeType));
    }
  }

  /** Normalises and checks the request, independently of any environment. */
  private RoleDefinition validate(CssRoleCreateRequest request) {
    String roleCode = request.roleCode().trim().toUpperCase(java.util.Locale.ROOT);
    String displayName = request.roleName().trim();
    String description = request.description() == null ? null : request.description().trim();

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

    return new RoleDefinition(roleCode, displayName, description, scopeType);
  }

  private boolean roleExists(int integrationId, String environment, String roleCode) {
    return cssApiService.getRoles(integrationId, environment).stream()
        .anyMatch(role -> roleCode.equals(role.name()));
  }

  private void requireRoleAbsent(int integrationId, String environment, String roleCode) {
    if (roleExists(integrationId, environment, roleCode)) {
      // Refused rather than treated as an edit. The role may already be granted
      // to people, and silently redefining what they hold is not what "create"
      // should do.
      throw FamHttpException.conflict(ErrorCode.INVALID_OPERATION,
          "A role named %s already exists in this application.".formatted(roleCode));
    }
  }

  /**
   * Creates the role, its scope marker and its sidecar in one environment.
   *
   * <p>Writes no audit: the caller decides when the change is complete enough to
   * record, which is not the same moment when several environments are involved.
   *
   * @return the CSS roles this call created, for the caller to undo if needed
   */
  private List<String> createArtifacts(
      int integrationId, String environment, RoleDefinition definition) {

    Set<String> existing = cssApiService.getRoles(integrationId, environment).stream()
        .map(CssRoleDto::name).collect(java.util.stream.Collectors.toCollection(HashSet::new));

    String labelRole =
        CssRoleNaming.buildLabelRoleName(definition.roleCode(), definition.displayName());
    // Absent when no description was given: a role whose name says enough gets
    // no second sidecar rather than an empty one.
    String descriptionRole = definition.description() == null || definition.description().isBlank()
        ? null
        : CssRoleNaming.buildDescriptionRoleName(definition.roleCode(), definition.description());
    List<String> created = new ArrayList<>();

    try {
      cssApiService.createRole(integrationId, environment, definition.roleCode());
      created.add(definition.roleCode());

      Optional<String> marker = CssRoleNaming.markerFor(definition.scopeType());
      if (marker.isPresent()) {
        if (!existing.contains(marker.get())
            && cssApiService.createRole(integrationId, environment, marker.get())) {
          created.add(marker.get());
        }
        cssApiService.addRoleComposites(
            integrationId, environment, definition.roleCode(), List.of(marker.get()));
      }

      if (!existing.contains(labelRole)
          && cssApiService.createRole(integrationId, environment, labelRole)) {
        created.add(labelRole);
      }

      if (descriptionRole != null && !existing.contains(descriptionRole)
          && cssApiService.createRole(integrationId, environment, descriptionRole)) {
        created.add(descriptionRole);
      }

    } catch (RuntimeException e) {
      rollback(integrationId, environment, created);
      throw e;
    }

    log.info("Created CSS role {} ({}) on integration {} ({}), scope {}.",
        definition.roleCode(), definition.displayName(), integrationId, environment,
        definition.scopeType() == null ? "none" : definition.scopeType());

    return created;
  }

  /**
   * Remove a role from an integration.
   *
   * <p><b>This revokes access.</b> Deleting a role in Keycloak takes it away from
   * everyone holding it; there is no separate step and no way back. The count of
   * affected people is read before anything is deleted and recorded in the audit,
   * because CSS keeps no trace of a role once it is gone.
   *
   * <p>One role on the screen is up to several in CSS, and all of them go:
   *
   * <pre>
   * FREP_EDITOR                                      the role
   * FREP_EDITOR_DISTRICT-DCC                         derived, one per scope granted
   * FREP_EDITOR_DISTRICT-DKA                         derived
   * FAM:LABEL:FREP_EDITOR:FREP Editor                sidecar, holds the description
   * </pre>
   *
   * <p><b>Scope markers are never removed.</b> {@code HAS_DISTRICT_ROLE} is shared
   * by every scoped role on the integration - {@link #createRole} creates it once
   * and reuses it - so deleting it would silently unscope the rest.
   *
   * <p>Deletion order is deliberate: the derived roles first, then the role, then
   * the sidecar. The derived roles are where a scoped role's access actually
   * lives, so if this fails partway the access is already gone rather than left
   * behind on a role the screen no longer shows.
   *
   * @return what was removed, so the screen can say so rather than guess
   */
  public CssRoleDeleteResultDto deleteRole(
      int integrationId, String environment, String roleName, Requester requester) {

    if (roleName == null || roleName.isBlank()) {
      throw FamHttpException.badRequest(
          ErrorCode.INVALID_REQUEST_PARAMETER, "A role name is required.");
    }
    if (CssRoleNaming.MARKERS.contains(roleName)) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_OPERATION,
          "%s is a scope marker shared by every scoped role in this application, "
              .formatted(roleName)
              + "not a role of its own. Removing it would unscope the others.");
    }
    if (CssRoleNaming.isSidecarRole(roleName)) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_OPERATION,
          "That is a name or description held on a sidecar role, not a role. It is "
              + "removed with the role it describes.");
    }

    List<CssRoleDto> roles = cssApiService.getRoles(integrationId, environment);

    if (roles.stream().noneMatch(role -> roleName.equals(role.name()))) {
      throw FamHttpException.notFound(ErrorCode.INVALID_OPERATION,
          "No role named %s exists in this application.".formatted(roleName));
    }

    // Derived roles carry the scope in their name; the base role is what the
    // screen shows. Anything whose base name is this role belongs to it.
    List<String> derived = roles.stream()
        .map(CssRoleDto::name)
        .filter(name -> !name.equals(roleName))
        .filter(name -> {
          CssRoleNaming.ScopedRoleName parsed = CssRoleNaming.parse(name);
          return parsed.scopeType() != null && roleName.equals(parsed.baseRoleName());
        })
        .toList();

    String displayName = sidecarText(roles, CssRoleNaming::parseLabel).get(roleName);

    // Both kinds: a role's name and its description are separate sidecars, and
    // leaving either behind would have it describe a role that no longer exists.
    List<String> sidecars = roles.stream()
        .map(CssRoleDto::name)
        .filter(name -> CssRoleNaming.parseLabel(name)
            .or(() -> CssRoleNaming.parseDescription(name))
            .map(label -> roleName.equals(label.roleCode()))
            .orElse(false))
        .toList();

    // Before deleting: afterwards the assignments are gone and uncountable.
    List<String> accessBearing = new ArrayList<>(derived);
    accessBearing.add(roleName);
    int membersAffected = countMembers(integrationId, environment, accessBearing);

    // Delegations naming this role, on FAM's own integration. Withdrawn FIRST,
    // for the same reason derived roles go before the base role: they carry
    // authority, and a delegation outliving its role is not inert. A grant
    // creates a role it cannot find, so a delegated administrator holding an
    // orphaned delegation could grant the deleted role and bring it back.
    List<String> delegations = delegationsNaming(integrationId, environment, roleName);
    List<String> removedDelegations = new ArrayList<>();

    for (String delegation : delegations) {
      try {
        cssApiService.deleteRole(ownIntegrationId(), famEnvironment(), delegation);
        removedDelegations.add(delegation);
      } catch (RuntimeException e) {
        log.error("Could not withdraw delegation {} while removing role {}: {}",
            delegation, roleName, e.getMessage());
        throw FamHttpException.internalError(ErrorCode.UNKNOWN_STATE,
            "Withdrew %s, then failed on %s. The role itself was not removed."
                .formatted(
                    removedDelegations.isEmpty() ? "nothing" : String.join(", ", removedDelegations),
                    delegation));
      }
    }

    List<String> removed = new ArrayList<>();
    List<String> ordered = new ArrayList<>(derived);
    ordered.add(roleName);
    ordered.addAll(sidecars);

    for (String name : ordered) {
      try {
        cssApiService.deleteRole(integrationId, environment, name);
        removed.add(name);
      } catch (RuntimeException e) {
        // Not rolled back, because a deletion cannot be undone - the roles
        // already removed are gone. Reporting what did go is the most useful
        // thing left to do.
        log.error("Removing role {} on integration {} ({}) failed after removing {}: {}",
            name, integrationId, environment, removed, e.getMessage());
        // UNKNOWN_STATE rather than a plain upstream error: the accurate thing to
        // report is that the application is now half-deleted, not that one call
        // failed.
        throw FamHttpException.internalError(ErrorCode.UNKNOWN_STATE,
            "Removed %s, then failed on %s. The rest may need removing by hand in the "
                .formatted(removed.isEmpty() ? "nothing" : String.join(", ", removed), name)
                + "CSS console.");
      }
    }

    log.info("Removed CSS role {} on integration {} ({}): {} role(s), {} delegation(s), "
        + "{} member(s) affected.",
        roleName, integrationId, environment, removed.size(), removedDelegations.size(),
        membersAffected);

    auditWriteService.storeRoleDeleted(
        requester, integrationId, environment, roleName, displayName, removed, membersAffected);

    return new CssRoleDeleteResultDto(
        roleName, removed, removedDelegations, membersAffected);
  }

  /**
   * Appoint somebody as a delegated administrator for one role.
   *
   * <p>Port of legacy's {@code create_access_control_privilege_many}. Appointing
   * <em>is</em> granting a role - the delegation role
   * {@code DELEGATED_ADMIN_<id>_<ENV>__<ROLE>} on FAM's own integration - so this
   * follows the grant path's shape: create the role if it does not exist yet,
   * then assign it.
   *
   * <p><b>One delegation per scope value.</b> Appointing somebody for three
   * districts creates three delegation roles, because a grant assigns per-scope
   * roles and a delegation must name exactly what it authorises. Delegating the
   * bare base role of a scoped role would authorise nothing.
   *
   * <p>The appointment itself is audited against the application being
   * administered rather than FAM's own integration: the change is "who may grant
   * FREP's Submitter role", and looking for it under FAM would be surprising.
   */
  public List<CssUserRoleAssignmentResult> appointDelegatedAdmin(
      int integrationId, String environment, CssDelegatedAdminRequest request,
      Requester requester) {

    authorizationService.requireDelegatedAdminManagement(requester, integrationId, environment);

    // Legacy's enforce_self_grant_guard. Without it an application administrator
    // could delegate themselves roles, which is pointless at best and, once the
    // tiers diverge further, a way to keep authority after losing the higher one.
    authorizationService.forbidSelfGrant(requester, request.userGuid());

    // A Business BCeID administrator may only appoint within their own
    // organisation, the same rule the grant path applies.
    targetOrganizationGuard.requireSameOrganization(
        requester, request.userType(), request.userGuid());

    Integer ownIntegrationId = ownIntegrationId();
    String username = cssUsername(request.userGuid(), request.userType());

    List<String> delegations = delegationRoleNames(integrationId, environment, request);

    Set<String> existing = new HashSet<>();
    cssApiService.getRoles(ownIntegrationId, famEnvironment())
        .forEach(role -> existing.add(role.name()));

    List<CssUserRoleAssignmentResult> results = new ArrayList<>();
    List<String> assignable = new ArrayList<>();

    for (String delegation : delegations) {
      try {
        boolean created = false;
        if (!existing.contains(delegation)) {
          cssApiService.createRole(ownIntegrationId, famEnvironment(), delegation);
          created = true;
        }
        assignable.add(delegation);
        results.add(new CssUserRoleAssignmentResult(
            delegation, created, false, null, EmailSendingStatus.NOT_REQUIRED));
      } catch (RuntimeException e) {
        log.warn("Could not create delegation role {}: {}", delegation, e.getMessage());
        results.add(CssUserRoleAssignmentResult.failed(delegation, e.getMessage()));
      }
    }

    if (assignable.isEmpty()) {
      return results;
    }

    try {
      cssApiService.assignUserRoles(ownIntegrationId, famEnvironment(), username, assignable);
      results.replaceAll(result -> assignable.contains(result.roleName())
          ? new CssUserRoleAssignmentResult(result.roleName(), result.roleCreated(), true, null,
              EmailSendingStatus.NOT_REQUIRED)
          : result);
    } catch (RuntimeException e) {
      log.error("Could not appoint {} as a delegated administrator: {}",
          username, e.getMessage());
      results.replaceAll(result -> assignable.contains(result.roleName())
          ? CssUserRoleAssignmentResult.failed(result.roleName(), e.getMessage())
          : result);
      return results;
    }

    log.info("Appointed {} as delegated administrator of {} on integration {} ({}).",
        username, delegations, integrationId, environment);

    auditWriteService.storeCssGranted(
        requester, request.userGuid(), request.userType().getCode(),
        integrationId, environment, request.roleName(), request.scopeType(), results);

    return results;
  }

  /**
   * Appoint somebody as an application administrator.
   *
   * <p>Simpler than a delegation, and deliberately so: an application
   * administrator is authorised over the <em>application</em>, so there is no
   * role to name and no scope to choose. One role,
   * {@code APP_ADMIN_<id>_<ENV>}, on FAM's own integration.
   *
   * <p>Same guard as appointing a delegated administrator - application
   * administrators and above - which means an application administrator can
   * appoint a peer. That matches what the tier already implies: they can already
   * grant every role the application defines, so a peer gains them nothing they
   * could not do themselves. Appointing into FAM's own integration remains
   * {@code FAM_ADMIN} only.
   */
  public CssUserRoleAssignmentResult appointApplicationAdmin(
      int integrationId, String environment, CssAdministratorAppointRequest request,
      Requester requester) {

    authorizationService.requireDelegatedAdminManagement(requester, integrationId, environment);
    authorizationService.forbidSelfGrant(requester, request.userGuid());
    targetOrganizationGuard.requireSameOrganization(
        requester, request.userType(), request.userGuid());

    Integer ownIntegrationId = ownIntegrationId();
    String username = cssUsername(request.userGuid(), request.userType());
    String roleName = FamAdminRole.appAdmin(integrationId, environment);

    boolean created = false;
    try {
      boolean exists = cssApiService.getRoles(ownIntegrationId, famEnvironment()).stream()
          .anyMatch(role -> roleName.equals(role.name()));
      if (!exists) {
        cssApiService.createRole(ownIntegrationId, famEnvironment(), roleName);
        created = true;
      }
      cssApiService.assignUserRoles(
          ownIntegrationId, famEnvironment(), username, List.of(roleName));
    } catch (RuntimeException e) {
      log.error("Could not appoint {} as an application administrator: {}",
          username, e.getMessage());
      return CssUserRoleAssignmentResult.failed(roleName, e.getMessage());
    }

    log.info("Appointed {} as application administrator of integration {} ({}).",
        username, integrationId, environment);

    CssUserRoleAssignmentResult result = new CssUserRoleAssignmentResult(
        roleName, created, true, null, EmailSendingStatus.NOT_REQUIRED);

    auditWriteService.storeCssGranted(
        requester, request.userGuid(), request.userType().getCode(),
        integrationId, environment, roleName, null, List.of(result));

    return result;
  }

  /** Remove somebody's application administrator role. */
  public void removeApplicationAdmin(
      int integrationId, String environment, CssAdministratorAppointRequest request,
      Requester requester) {

    authorizationService.requireDelegatedAdminManagement(requester, integrationId, environment);
    // Removing yourself is refused for the same reason appointing yourself is:
    // an administrator who can drop their own tier mid-session leaves the screen
    // in a state that disagrees with their token until they sign in again.
    authorizationService.forbidSelfGrant(requester, request.userGuid());

    String roleName = FamAdminRole.appAdmin(integrationId, environment);
    cssApiService.removeUserRole(ownIntegrationId(), famEnvironment(),
        cssUsername(request.userGuid(), request.userType()), roleName);

    log.info("Removed application administration of integration {} ({}) from {}.",
        integrationId, environment, request.userGuid());

    auditWriteService.storeCssRevoked(
        requester, request.userGuid(), request.userType().getCode(),
        integrationId, environment, roleName, null, List.of(roleName));
  }

  /**
   * Withdraw one delegation.
   *
   * <p>Removes the assignment, not the delegation role itself - the role stays
   * defined and may be held by others, exactly as revoking an application role
   * leaves that role in place.
   */
  public void removeDelegatedAdmin(
      int integrationId, String environment, CssDelegatedAdminRequest request,
      Requester requester) {

    authorizationService.requireDelegatedAdminManagement(requester, integrationId, environment);
    authorizationService.forbidSelfGrant(requester, request.userGuid());
    targetOrganizationGuard.requireSameOrganization(
        requester, request.userType(), request.userGuid());

    String username = cssUsername(request.userGuid(), request.userType());
    List<String> delegations = delegationRoleNames(integrationId, environment, request);

    for (String delegation : delegations) {
      cssApiService.removeUserRole(
          ownIntegrationId(), famEnvironment(), username, delegation);
    }

    log.info("Removed delegated administration of {} from {} on integration {} ({}).",
        delegations, username, integrationId, environment);

    auditWriteService.storeCssRevoked(
        requester, request.userGuid(), request.userType().getCode(),
        integrationId, environment, request.roleName(), request.scopeType(), delegations);
  }

  /** The delegation roles a request describes, one per scope value. */
  private static List<String> delegationRoleNames(
      int integrationId, String environment, CssDelegatedAdminRequest request) {

    if (request.scopeValues().isEmpty()) {
      return List.of(FamAdminRole.delegation(integrationId, environment, request.roleName()));
    }
    if (request.scopeType() == null || request.scopeType().isBlank()) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "scope_type is required when scope_values are given.");
    }
    // Distinct, for the same reason the grant path dedupes: two identical scope
    // values would name the same delegation twice and report it twice.
    return new ArrayList<>(new LinkedHashSet<>(request.scopeValues().stream()
        .map(value -> FamAdminRole.delegation(integrationId, environment,
            CssRoleNaming.buildScopedRoleName(request.roleName(), request.scopeType(), value)))
        .toList()));
  }

  /** FAM's own integration, where every administrative role lives. */
  private Integer ownIntegrationId() {
    Integer ownIntegrationId = famProperties.integration() == null
        || famProperties.integration().css() == null
        ? null
        : famProperties.integration().css().ownIntegrationId();

    if (ownIntegrationId == null) {
      throw FamHttpException.internalError(ErrorCode.UNKNOWN_STATE,
          "FAM's own CSS integration id is not configured, so delegated "
              + "administrators cannot be managed. Set CSS_OWN_INTEGRATION_ID.");
    }
    return ownIntegrationId;
  }

  /** The environment of FAM's own integration, which is FAM's deployment one. */
  private String famEnvironment() {
    return famProperties.deploymentEnvironment();
  }

  private String cssUsername(String userGuid, ca.bc.gov.nrs.fam.constants.UserType userType) {
    try {
      FamProperties.Integration.Css css = famProperties.integration().css();
      return CssRoleNaming.buildUsername(userGuid, userType,
          css.idpAliases().idir(), css.idpAliases().bceidBusiness());
    } catch (IllegalArgumentException e) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER, e.getMessage());
    }
  }

  /**
   * The people administering one application, at one tier.
   *
   * <p>Backs the Delegated admins and Application admins tabs.
   *
   * <p><b>Read from FAM's own integration, not the application's.</b> An
   * administrator holds {@code APP_ADMIN_<id>_<ENV>} or
   * {@code DELEGATED_ADMIN_<id>_<ENV>} on FAM's integration - the application is
   * named inside the role rather than implied by where the role lives, because a
   * token only ever carries roles of the client it was issued to. This is why
   * administrators never appear in the application's own user list, and why these
   * tabs are a second read rather than a filter over the first.
   *
   * <p>The environment read is FAM's own deployment environment, not the
   * application's: the role sits on the FAM client the caller signed in to, while
   * the environment inside the role name is the one being administered. A FAM
   * production deployment therefore reads its own prod environment to find who
   * administers a dev application.
   *
   * @throws FamHttpException when FAM's own integration id is not configured -
   *     without it there is nowhere to look, and guessing would list the wrong
   *     application's administrators
   */
  public List<CssAdministratorRowDto> getAdministrators(
      int integrationId, String environment, AdminRoleAuthGroup tier) {

    Integer ownIntegrationId = famProperties.integration() == null
        || famProperties.integration().css() == null
        ? null
        : famProperties.integration().css().ownIntegrationId();

    if (ownIntegrationId == null) {
      throw FamHttpException.internalError(ErrorCode.UNKNOWN_STATE,
          "FAM's own CSS integration id is not configured, so its administrators "
              + "cannot be read. Set CSS_OWN_INTEGRATION_ID.");
    }

    String famEnvironment = famProperties.deploymentEnvironment();

    // The two tiers are shaped differently. An application administrator holds
    // one role; a delegated administrator holds one role PER DELEGATION, so the
    // roster is the union of everyone holding any delegation for this
    // application - and each row names the role that person was delegated.
    List<String> roleNames = switch (tier) {
      case APP_ADMIN -> List.of(FamAdminRole.appAdmin(integrationId, environment));
      case DELEGATED_ADMIN -> delegationRolesOn(
          ownIntegrationId, famEnvironment, integrationId, environment);
      case FAM_ADMIN -> throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "FAM administrators are not specific to an application.");
    };

    List<CssAdministratorRowDto> rows = new ArrayList<>();
    for (String roleName : roleNames) {
      for (CssApiService.CssUserDto user
          : holdersOf(ownIntegrationId, famEnvironment, roleName)) {

        rows.add(new CssAdministratorRowDto(
            user.displayUsername(),
            CssRoleNaming.guidFromUsername(user.username()).orElse(null),
            CssRoleNaming.domainFromUsername(user.username()).orElse(null),
            user.firstName(),
            user.lastName(),
            user.email(),
            tier,
            roleName,
            // Null for an application administrator, who is not delegated any
            // single role - they administer everything the application defines.
            FamAdminRole.delegatedRoleOf(roleName).orElse(null)));
      }
    }

    log.debug("Returning {} {} row(s) for integration {} ({}).",
        rows.size(), tier, integrationId, environment);
    return rows;
  }

  /**
   * Every delegation role defined for one application.
   *
   * <p>Read from FAM's own integration and filtered by prefix, because a
   * delegation names the application inside the role. A plain
   * {@code DELEGATED_ADMIN_<id>_<ENV>} marker is included too, so anybody
   * appointed before delegations existed still appears.
   */
  private List<String> delegationRolesOn(
      int ownIntegrationId, String famEnvironment, int integrationId, String environment) {

    String tierRole = FamAdminRole.delegatedAdmin(integrationId, environment);

    return cssApiService.getRoles(ownIntegrationId, famEnvironment).stream()
        .map(CssRoleDto::name)
        .filter(name -> name.equalsIgnoreCase(tierRole)
            || name.toUpperCase(java.util.Locale.ROOT)
                .startsWith(tierRole.toUpperCase(java.util.Locale.ROOT) + "__"))
        .toList();
  }

  /**
   * Who holds one administrative role, treating "no such role" as nobody.
   *
   * <p>An administrative role only exists once somebody has been appointed, so
   * CSS answering 404 means an empty roster rather than a failure. Any other
   * status is a real problem and is rethrown.
   */
  private List<CssApiService.CssUserDto> holdersOf(
      int ownIntegrationId, String famEnvironment, String roleName) {

    try {
      return cssApiService.getUsersWithRole(ownIntegrationId, famEnvironment, roleName);
    } catch (UpstreamException e) {
      if (e.getStatus() != HttpStatus.NOT_FOUND) {
        throw e;
      }
      log.debug("No {} role on FAM's integration yet; reporting nobody.", roleName);
      return List.of();
    }
  }

  /**
   * How many people hold each role, the roles derived from it included.
   *
   * <p>One upstream request per role, which is why this is its own endpoint
   * rather than part of {@link #getRoles}: the role picker on the grant screen
   * needs roles, not counts, and should not pay for them.
   */
  public List<CssRoleMemberCountDto> getRoleMemberCounts(int integrationId, String environment) {
    List<CssRoleDto> roles = cssApiService.getRoles(integrationId, environment);

    if (roles.size() > FAN_OUT_WARN_THRESHOLD) {
      log.warn("Counting members for integration {} ({}) needs {} requests, one per role.",
          integrationId, environment, roles.size());
    }

    // Distinct users per base role. A sidecar is granted to nobody, and a marker
    // is a composite child rather than something held, so neither is queried.
    Map<String, Set<String>> byBaseRole = new HashMap<>();
    for (CssRoleDto role : roles) {
      if (CssRoleNaming.isSidecarRole(role.name())
          || CssRoleNaming.MARKERS.contains(role.name())) {
        continue;
      }
      String baseRole = CssRoleNaming.parse(role.name()).baseRoleName();
      Set<String> members = byBaseRole.computeIfAbsent(baseRole, key -> new HashSet<>());
      for (CssApiService.CssUserDto user
          : cssApiService.getUsersWithRole(integrationId, environment, role.name())) {
        members.add(user.username());
      }
    }

    return byBaseRole.entrySet().stream()
        .map(entry -> new CssRoleMemberCountDto(entry.getKey(), entry.getValue().size()))
        .toList();
  }

  /**
   * Delegations that name a role, including its per-scope ones.
   *
   * <p>Matched by parsing rather than by prefix: {@code FREP_EDITOR} must not
   * carry off {@code FREP_EDITOR_EXTRA}'s delegations, exactly as it does not
   * carry off its derived roles.
   *
   * <p>Returns nothing when FAM's own integration id is unset - there is nowhere
   * to look, and the deletion of the application's role should not be blocked by
   * a configuration fault. The startup warning already covers that case.
   */
  private List<String> delegationsNaming(
      int integrationId, String environment, String roleName) {

    Integer ownIntegrationId = famProperties.integration() == null
        || famProperties.integration().css() == null
        ? null
        : famProperties.integration().css().ownIntegrationId();

    if (ownIntegrationId == null) {
      log.warn("Cannot withdraw delegations for {}: FAM's own integration id is not set. "
          + "Any delegation naming it is now orphaned.", roleName);
      return List.of();
    }

    return cssApiService.getRoles(ownIntegrationId, famEnvironment()).stream()
        .map(CssRoleDto::name)
        .filter(name -> FamAdminRole.delegatedRoleOf(name)
            .map(delegated -> roleName.equals(delegated)
                || roleName.equals(CssRoleNaming.parse(delegated).baseRoleName()))
            .orElse(false))
        // Only this application's: another application may define a role of the
        // same name, and its delegations are not ours to withdraw.
        .filter(name -> name.toUpperCase(java.util.Locale.ROOT).startsWith(
            FamAdminRole.delegatedAdmin(integrationId, environment)
                .toUpperCase(java.util.Locale.ROOT) + "__"))
        .toList();
  }

  /**
   * Distinct people holding any of these roles.
   *
   * <p>By username rather than by row: someone granted two districts holds two
   * roles but is one person losing access.
   */
  private int countMembers(int integrationId, String environment, List<String> roleNames) {
    Set<String> members = new HashSet<>();
    for (String roleName : roleNames) {
      for (CssApiService.CssUserDto user
          : cssApiService.getUsersWithRole(integrationId, environment, roleName)) {
        members.add(user.username());
      }
    }
    return members.size();
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

    authorizationService.forbidSelfGrant(requester, request.userGuid());

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

    // After scope has been applied, so a delegation for one district does not
    // authorise another. A requester with no delegation covering every role here
    // is refused outright rather than granted the subset they may.
    authorizationService.requireGrantableRoles(
        requester, integrationId, environment, targetRoles);

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

    authorizationService.forbidSelfGrant(requester, request.userGuid());
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

    // Revoking is delegated the same way granting is: taking a role away is as
    // much a change to somebody's access as giving it, and legacy checked both.
    authorizationService.requireGrantableRoles(
        requester, integrationId, environment, List.of(cssRoleName));

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

    Map<String, String> displayNames = sidecarText(roles, CssRoleNaming::parseLabel);

    List<CssUserRoleRowDto> rows = new ArrayList<>();
    for (CssRoleDto role : roles) {
      // A sidecar holds a description and is granted to nobody. Skipping it saves
      // a request, and means a row could never appear for one if somebody
      // assigned it by hand in the CSS console.
      if (CssRoleNaming.isSidecarRole(role.name())) {
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
            displayNames.get(parsed.baseRoleName()),
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
