package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.dto.CssIntegrationDto;
import ca.bc.gov.nrs.fam.dto.CssRoleDto;
import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
import ca.bc.gov.nrs.fam.dto.ScopeDto;
import ca.bc.gov.nrs.fam.dto.SelfApplicationRoleDto;
import ca.bc.gov.nrs.fam.dto.SelfPermissionDto;
import ca.bc.gov.nrs.fam.integration.CssApiService;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The signed-in user's own administrative permissions, as "My permissions" shows
 * them.
 *
 * <p>Replaces {@code admin_user_access_privilege}, which read FAM's
 * {@code fam_application_admin} and {@code fam_access_control_privilege} tables.
 * Those tables went to CSS with delegated administration, so the same
 * information now comes from the role names on the caller's own token.
 *
 * <p><b>Read from the requester, not from a parameter.</b> There is no way to
 * ask this for somebody else, which is what makes it safe to expose to any
 * signed-in user: the answer is always about the caller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfPermissionService {

  private final CssApiService cssApiService;
  private final FamProperties famProperties;

  /**
   * The caller's administrative permissions, one row per administrative role.
   *
   * <p>FAM_ADMIN produces a single row naming no application, because it
   * administers every one - listing every integration against it would be a
   * lie the moment a new integration appeared.
   *
   * <p>Application names are resolved from CSS in one request, shared across
   * every row. A role naming an integration CSS no longer returns still gets a
   * row, labelled with its id: the caller does hold that role, and hiding it
   * would make the screen disagree with what the token says.
   *
   * <p>Roles that are not FAM's own - an application's roles, were any to appear
   * here - are ignored rather than shown as administrative.
   */
  public List<SelfPermissionDto> getSelfPermissions(Requester requester) {
    List<String> roles = requester.accessRoles() == null
        ? List.of()
        : requester.accessRoles();

    List<SelfPermissionDto> permissions = new ArrayList<>();
    Map<Integer, String> names = null;

    for (String role : roles) {
      AdminRoleAuthGroup tier = FamAdminRole.tierOf(role).orElse(null);
      if (tier == null) {
        continue;
      }

      if (tier == AdminRoleAuthGroup.FAM_ADMIN) {
        permissions.add(new SelfPermissionDto(
            null, null, "All applications", tier, describe(tier), role));
        continue;
      }

      var target = FamAdminRole.targetOf(role).orElse(null);
      if (target == null) {
        // A name that parses as a tier but not as an application. Skipped rather
        // than guessed at - see FamAdminRole.targetOf.
        log.warn("Ignoring malformed administrative role name: {}", role);
        continue;
      }

      // Fetched once, and only if some role actually needs a name.
      if (names == null) {
        names = integrationNames();
      }

      permissions.add(new SelfPermissionDto(
          target.cssIntegrationId(),
          target.cssEnvironment(),
          names.getOrDefault(
              target.cssIntegrationId(),
              "Integration %d".formatted(target.cssIntegrationId())),
          tier,
          describe(tier),
          role));
    }

    permissions.sort(Comparator
        .comparing(SelfPermissionDto::applicationName, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(p -> p.environment() == null ? "" : p.environment()));

    log.debug("Returning {} administrative permission(s) for {}.",
        permissions.size(), requester.userName());
    return permissions;
  }

  /**
   * Every application role the caller holds, across every integration FAM can
   * see.
   *
   * <p><b>"Everywhere" means every integration FAM's CSS API account owns.</b>
   * That account is team scoped, so a role held in an application belonging to
   * another team is invisible here - not filtered out, genuinely unreadable. The
   * screen says so rather than implying the list is exhaustive.
   *
   * <p><b>Cost.</b> CSS has no cross-integration view of a user, so this is one
   * request per integration and environment, plus one more per pair where the
   * caller actually holds something - to read the sidecars that carry role
   * descriptions. An integration where they hold nothing costs a single request
   * and is dropped.
   *
   * <p>A failure against one environment is logged and skipped rather than
   * failing the whole screen: an integration being unreachable should cost its
   * own rows, not everyone else's.
   */
  public List<SelfApplicationRoleDto> getSelfApplicationRoles(Requester requester) {
    String username;
    try {
      FamProperties.Integration.Css css = famProperties.integration().css();
      username = CssRoleNaming.buildUsername(requester.userGuid(), requester.userType(),
          css.idpAliases().idir(), css.idpAliases().bceidBusiness());
    } catch (IllegalArgumentException e) {
      // No GUID or an identity type CSS cannot name. Nothing to look up.
      log.warn("Cannot build a CSS username for {}: {}", requester.userName(), e.getMessage());
      return List.of();
    }

    List<SelfApplicationRoleDto> rows = new ArrayList<>();

    for (CssIntegrationDto integration : integrations()) {
      if (integration.id() == null) {
        continue;
      }
      for (String environment : integration.environments()) {
        rows.addAll(rolesIn(integration, environment, username));
      }
    }

    rows.sort(Comparator
        .comparing(SelfApplicationRoleDto::applicationName, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(SelfApplicationRoleDto::environment)
        .thenComparing(SelfApplicationRoleDto::roleName));

    log.debug("Returning {} application role(s) for {}.", rows.size(), requester.userName());
    return rows;
  }

  private List<SelfApplicationRoleDto> rolesIn(
      CssIntegrationDto integration, String environment, String username) {

    List<CssRoleDto> held;
    try {
      held = cssApiService.getUserRoles(integration.id(), environment, username);
    } catch (RuntimeException e) {
      log.warn("Could not read {}'s roles in integration {} ({}): {}",
          username, integration.id(), environment, e.getMessage());
      return List.of();
    }

    // Markers and sidecars are FAM's own bookkeeping, not something a person is
    // meaningfully "granted" - and a sidecar is granted to nobody in any case.
    List<CssRoleDto> visible = held.stream()
        .filter(role -> !CssRoleNaming.isSidecarRole(role.name()))
        .filter(role -> !CssRoleNaming.MARKERS.contains(role.name()))
        .toList();

    if (visible.isEmpty()) {
      return List.of();
    }

    // Only now worth a second request: descriptions live on sidecar roles in the
    // integration's own role listing.
    Map<String, String> displayNames;
    Map<String, String> descriptions;
    try {
      List<CssRoleDto> defined = cssApiService.getRoles(integration.id(), environment);
      displayNames = sidecarText(defined, CssRoleNaming::parseLabel);
      descriptions = sidecarText(defined, CssRoleNaming::parseDescription);
    } catch (RuntimeException e) {
      log.warn("Could not read role descriptions for integration {} ({}): {}",
          integration.id(), environment, e.getMessage());
      displayNames = Map.of();
      descriptions = Map.of();
    }

    List<SelfApplicationRoleDto> rows = new ArrayList<>();
    for (CssRoleDto role : visible) {
      CssRoleNaming.ScopedRoleName parsed = CssRoleNaming.parse(role.name());
      rows.add(new SelfApplicationRoleDto(
          integration.id(),
          environment,
          integration.projectName() == null
              ? "Integration %d".formatted(integration.id())
              : integration.projectName(),
          role.name(),
          parsed.baseRoleName(),
          displayNames.get(parsed.baseRoleName()),
          descriptions.get(parsed.baseRoleName()),
          parsed.scopes().stream().map(ScopeDto::of).toList()));
    }
    return rows;
  }

  /**
   * Role code to sidecar text, for either sidecar.
   *
   * <p>Mirrors {@code CssIntegrationService.sidecarText}. Duplicated rather than
   * shared to keep this service free of that one, which carries the whole grant
   * path with it.
   */
  private static Map<String, String> sidecarText(
      List<CssRoleDto> roles,
      java.util.function.Function<String, java.util.Optional<CssRoleNaming.RoleLabel>> parser) {

    Map<String, String> text = new HashMap<>();
    for (CssRoleDto role : roles) {
      parser.apply(role.name()).ifPresent(label -> text.put(label.roleCode(), label.text()));
    }
    return text;
  }

  private List<CssIntegrationDto> integrations() {
    try {
      return cssApiService.getIntegrations();
    } catch (RuntimeException e) {
      log.warn("Could not list integrations from CSS: {}", e.getMessage());
      return List.of();
    }
  }

  private Map<Integer, String> integrationNames() {
    Map<Integer, String> names = new HashMap<>();
    try {
      for (CssIntegrationDto integration : cssApiService.getIntegrations()) {
        if (integration.id() != null) {
          names.put(integration.id(), integration.projectName());
        }
      }
    } catch (RuntimeException e) {
      // Best effort: the caller's permissions are known from their token, and a
      // CSS outage should degrade the names rather than empty the screen.
      log.warn("Could not resolve application names from CSS: {}", e.getMessage());
    }
    return names;
  }

  private static String describe(AdminRoleAuthGroup tier) {
    return switch (tier) {
      case FAM_ADMIN -> "FAM administrator";
      case APP_ADMIN -> "Application administrator";
      case DELEGATED_ADMIN -> "Delegated administrator";
    };
  }
}
