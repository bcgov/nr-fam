package ca.bc.gov.nrs.fam.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Roles now come off the token rather than out of the database.
 *
 * <p>CSS assigns Keycloak roles, so they arrive on the token already - there is
 * nothing left to look up.
 */
@DisplayName("AccessRoleResolver (roles read from the access token)")
class AccessRoleResolverTest {

  private final AccessRoleResolver resolver = new AccessRoleResolver(new TokenClaimsReader());

  private static Jwt jwt(Map<String, Object> claims) {
    Map<String, Object> withSub = new HashMap<>(claims);
    withSub.putIfAbsent("sub", "keycloak-sub");
    return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300),
        Map.of("alg", "RS256"), withSub);
  }

  @Test
  @DisplayName("reads roles from the client_roles claim CSS emits")
  void readsClientRolesClaim() {
    Jwt token = jwt(Map.of(
        "azp", "fam-console",
        "client_roles", List.of("FAM_ADMIN", "FOM_DEV_ADMIN")));

    assertThat(resolver.resolveAccessRoles(token))
        .containsExactly("FAM_ADMIN", "FOM_DEV_ADMIN");
  }

  @Test
  @DisplayName("falls back to resource_access when the realm uses stock Keycloak mappers")
  void fallsBackToResourceAccess() {
    // Which claim appears depends on the realm's mappers, so both are read.
    Jwt token = jwt(Map.of(
        "azp", "fam-console",
        "resource_access", Map.of(
            "fam-console", Map.of("roles", List.of("FAM_ADMIN")),
            "some-other-client", Map.of("roles", List.of("SHOULD_NOT_APPEAR")))));

    assertThat(resolver.resolveAccessRoles(token)).containsExactly("FAM_ADMIN");
  }

  @Test
  @DisplayName("prefers client_roles when both claims are present")
  void prefersClientRoles() {
    Jwt token = jwt(Map.of(
        "azp", "fam-console",
        "client_roles", List.of("FROM_CLIENT_ROLES"),
        "resource_access", Map.of("fam-console", Map.of("roles", List.of("FROM_RESOURCE")))));

    assertThat(resolver.resolveAccessRoles(token)).containsExactly("FROM_CLIENT_ROLES");
  }

  @Test
  @DisplayName("a token with no roles claim yields none, rather than failing")
  void noRolesClaimYieldsNone() {
    // Unauthorised everywhere is the correct outcome, not an error.
    assertThat(resolver.resolveAccessRoles(jwt(Map.of("azp", "fam-console")))).isEmpty();
  }

  @Test
  @DisplayName("ignores resource_access entries for other clients")
  void ignoresOtherClientsRoles() {
    // The token is scoped to one client; another client's roles are not the
    // caller's roles here.
    Jwt token = jwt(Map.of(
        "azp", "fom-app",
        "resource_access", Map.of("fam-console", Map.of("roles", List.of("FAM_ADMIN")))));

    assertThat(resolver.resolveAccessRoles(token)).isEmpty();
  }

  @Test
  @DisplayName("a downstream application's token cannot carry FAM admin roles")
  void downstreamTokenCarriesOnlyItsOwnRoles() {
    // This used to need an explicit "only when signing in through FAM" check.
    // Now it holds by construction: the token carries the roles of the client it
    // was issued to, and FAM's admin roles live on FAM's own integration.
    Jwt fomToken = jwt(Map.of(
        "azp", "fom-app",
        "client_roles", List.of("FOM_REVIEWER", "FOM_SUBMITTER")));

    List<String> roles = resolver.resolveAccessRoles(fomToken);

    assertThat(roles).containsExactly("FOM_REVIEWER", "FOM_SUBMITTER");
    assertThat(Requester.builder().accessRoles(roles).build().canManageAccess(22264, "dev"))
        .isFalse();
  }

  @Test
  @DisplayName("resolved roles drive the administrative tiers")
  void resolvedRolesDriveTiers() {
    // The names are matched literally: FAM's own CSS integration has to define
    // roles of exactly this shape.
    Jwt token = jwt(Map.of(
        "azp", "fam-console",
        "client_roles", List.of("FAM_ADMIN", "APP_ADMIN_22264_DEV")));

    Requester requester =
        Requester.builder().accessRoles(resolver.resolveAccessRoles(token)).build();

    assertThat(requester.isFamAdmin()).isTrue();
    assertThat(requester.canManageAccess(22264, "dev")).isTrue();
    // FAM_ADMIN administers everything, so an unrelated application still passes.
    assertThat(requester.canManageAccess(99999, "prod")).isTrue();
  }

  @Test
  @DisplayName("tolerates a malformed resource_access shape")
  void toleratesMalformedResourceAccess() {
    // A realm misconfiguration should not 500 every authenticated request.
    Jwt token = jwt(Map.of("azp", "fam-console", "resource_access", "not-a-map"));

    assertThat(resolver.resolveAccessRoles(token)).isEmpty();
  }
}
