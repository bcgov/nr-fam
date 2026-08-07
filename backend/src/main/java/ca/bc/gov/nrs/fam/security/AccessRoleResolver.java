package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.repository.FamApplicationAdminRepository;
import ca.bc.gov.nrs.fam.repository.FamApplicationRepository;
import ca.bc.gov.nrs.fam.repository.FamRoleRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a caller's effective access roles.
 *
 * <p>This is the half of the Cognito pre-token-generation Lambda
 * ({@code auth_function.access_token_groups_override}) that cannot move to
 * Keycloak. Cognito ran a database query at login and injected the result into
 * {@code cognito:groups}; a Keycloak realm cannot do that without a custom SPI, so
 * FAM resolves the same set per request instead.
 *
 * <p>The practical difference is that roles are now <strong>always current</strong>
 * rather than fixed at login. Revoking access takes effect on the next request
 * instead of when the token is refreshed - stricter than before, not looser.
 *
 * <p>Two kinds of role are produced, exactly as upstream:
 *
 * <ol>
 *   <li>Role names assigned to the user within the application the token was
 *       issued for, excluding expired assignments.
 *   <li>When - and only when - signing in through FAM itself,
 *       {@code <APPLICATION_NAME>_ADMIN} for every application the user
 *       administers. This is what {@link Requester#isAdminOf} matches on.
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessRoleResolver {

  private static final String ADMIN_ROLE_SUFFIX = "_ADMIN";

  private final FamRoleRepository roleRepository;
  private final FamApplicationAdminRepository applicationAdminRepository;
  private final FamApplicationRepository applicationRepository;

  /**
   * @param oidcClientId the client the token was issued to, which identifies the
   *     application being used. Null or unknown yields no roles rather than an
   *     error - an unrecognised client is simply unauthorised everywhere.
   */
  @Transactional(readOnly = true)
  public List<String> resolveAccessRoles(
      String userGuid, String userTypeCode, String oidcClientId) {

    if (oidcClientId == null || oidcClientId.isBlank()) {
      log.debug("Token has no client id; resolving no access roles.");
      return List.of();
    }

    List<String> roles = new ArrayList<>(
        roleRepository.findRoleNamesForUserAndClient(userGuid, userTypeCode, oidcClientId));

    if (isSignedInThroughFam(oidcClientId)) {
      // Application-admin authority is only meaningful inside FAM's own console;
      // it must not leak into a downstream application's token.
      applicationAdminRepository
          .findAdministeredApplicationNames(userGuid, userTypeCode)
          .forEach(applicationName ->
              roles.add(applicationName.toUpperCase(Locale.ROOT) + ADMIN_ROLE_SUFFIX));
    }

    log.debug("Resolved {} access role(s) for client {}", roles.size(), oidcClientId);
    return List.copyOf(roles);
  }

  /** Whether this OIDC client belongs to FAM's own application record. */
  private boolean isSignedInThroughFam(String oidcClientId) {
    Optional<FamApplication> application =
        applicationRepository.findByOidcClientId(oidcClientId);
    return application
        .map(app -> FamConstants.APPLICATION_FAM.equals(app.getApplicationName()))
        .orElse(false);
  }
}
