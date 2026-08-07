package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.repository.FamApplicationRepository;
import ca.bc.gov.nrs.fam.repository.FamRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorisation for the external API.
 *
 * <p>Port of {@code router_guards.authorize_ext_api_by_app_role}. This surface is
 * for other applications' own OIDC clients, so it is exempt from the FAM-client
 * check that guards the internal API ({@link FamClientTokenFilter}) and is
 * authorised differently:
 *
 * <ol>
 *   <li>the token's client id must map to a known application, which is also how
 *       the caller's scope is established - an application can only ever see its
 *       own users;
 *   <li>the caller must hold a role within that application carrying
 *       {@code call_api_flag}, or a client-scoped child of one.
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalApiAuthorizationService {

  private final FamApplicationRepository applicationRepository;
  private final FamRoleRepository roleRepository;
  private final TokenClaimsReader claimsReader;

  /**
   * Resolve and authorise the calling application.
   *
   * @return the application the token belongs to; every query is scoped to it
   * @throws FamHttpException 403 when the client id is unknown, or the caller
   *     holds no role permitting external API calls
   */
  @Transactional(readOnly = true)
  public FamApplication authorize(Jwt jwt, Requester requester) {
    String appClientId = claimsReader.appClientId(jwt);

    FamApplication application = applicationRepository.findByOidcClientId(appClientId)
        .orElseThrow(() -> FamHttpException.forbidden(ErrorCode.INVALID_OPERATION,
            "Token contains invalid application client id " + mask(appClientId)));

    boolean allowed = roleRepository.hasExternalApiPermission(
        application.getApplicationId(), requester.userName());

    if (!allowed) {
      log.debug("Requester {} holds no call-api role in application {}",
          requester.userName(), application.getApplicationName());
      throw FamHttpException.forbidden(
          ErrorCode.PERMISSION_REQUIRED, "No permission to call the external API.");
    }

    return application;
  }

  /**
   * Resolve the calling application without the role check.
   *
   * <p>Used by the role-metadata endpoint, which reports the caller's own access:
   * requiring a call-api role first would make "what may I do?" unanswerable for
   * exactly the users who need to ask.
   */
  @Transactional(readOnly = true)
  public FamApplication resolveApplication(Jwt jwt) {
    String appClientId = claimsReader.appClientId(jwt);

    return applicationRepository.findByOidcClientId(appClientId)
        .orElseThrow(() -> FamHttpException.forbidden(ErrorCode.INVALID_OPERATION,
            "Token contains invalid application client id " + mask(appClientId)));
  }

  /** Only the first few characters are logged or returned; the rest is a secret-ish id. */
  private static String mask(String value) {
    if (value == null) {
      return "null";
    }
    int visible = Math.min(5, value.length());
    return value.substring(0, visible) + "*".repeat(Math.max(0, value.length() - visible));
  }
}
