package ca.bc.gov.nrs.fam.security;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Resolves a caller's effective access roles from their access token.
 *
 * <p>Roles live in CSS now, not in {@code fam_role} and {@code fam_user_role_xref},
 * and CSS assigns them as Keycloak roles - so they arrive on the token already.
 * There is nothing left to look up.
 *
 * <p>This reverses a decision made earlier in the port. Cognito's
 * pre-token-generation Lambda ran a database query at login and injected the
 * result into {@code cognito:groups}; a Keycloak realm cannot do that without a
 * custom SPI, so the port resolved the same set from the database per request
 * instead. With CSS as the source of truth, the token is authoritative again.
 *
 * <h2>What this costs</h2>
 *
 * <p>Resolving per request meant a revocation took effect on the very next call.
 * Reading from the token means it takes effect when the token is next refreshed -
 * every three minutes, per {@code AuthProvider}'s refresh interval. That window
 * is the price of removing the tables, and it is worth knowing about: a user
 * whose access is pulled keeps it for up to one refresh cycle.
 *
 * <h2>Admin roles</h2>
 *
 * <p>{@link Requester#isAdminOf} matches {@code <APPLICATION_NAME>_ADMIN}, so
 * FAM's own CSS integration must define a role of that shape for each
 * application an admin may administer, plus {@code FAM_ADMIN} for FAM itself.
 * Nothing derives these names - they are matched literally against the token.
 *
 * <p>The old "only add admin roles when signing in through FAM" rule is no longer
 * a rule to enforce: a token issued to a downstream application carries only that
 * application's roles, so FAM's admin roles cannot appear in it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessRoleResolver {

  private final TokenClaimsReader claimsReader;

  /**
   * The caller's roles, as carried on the token.
   *
   * <p>A token with no roles claim yields none, which is simply unauthorised
   * everywhere rather than an error.
   */
  public List<String> resolveAccessRoles(Jwt jwt) {
    List<String> roles = claimsReader.accessRoles(jwt);

    log.debug("Resolved {} access role(s) from the token for client {}",
        roles.size(), claimsReader.appClientId(jwt));

    return roles;
  }
}
