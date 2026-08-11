package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Reads FAM's identity claims out of a BC Gov SSO (Keycloak) access token.
 *
 * <p>This is the whole seam where FAM's move off AWS Cognito lands. Cognito
 * carried identity in {@code custom:idp_*} attributes and group membership in
 * {@code cognito:groups}; Keycloak publishes per-provider claims documented at
 * https://github.com/bcgov/sso-docs (Identity Mappers).
 *
 * <p>Claim names by provider:
 *
 * <table border="1">
 *   <caption>Keycloak standard-realm claims</caption>
 *   <tr><th>FAM field</th><th>IDIR</th><th>Business BCeID</th></tr>
 *   <tr><td>user name</td><td>{@code idir_username}</td><td>{@code bceid_username}</td></tr>
 *   <tr><td>user GUID</td><td>{@code idir_user_guid}</td><td>{@code bceid_user_guid}</td></tr>
 *   <tr><td>business GUID</td><td>-</td><td>{@code bceid_business_guid}</td></tr>
 * </table>
 *
 * <p><strong>Roles are not read from the token.</strong> Cognito's pre-token
 * Lambda injected FAM's roles into {@code cognito:groups} at login. Keycloak
 * cannot run that query without a custom SPI. With CSS as the source of truth
 * the roles are Keycloak roles, so they arrive on the token directly - see
 * {@link AccessRoleResolver}.
 */
@Slf4j
@Component
public class TokenClaimsReader {

  private static final String CLAIM_IDENTITY_PROVIDER = "identity_provider";
  private static final String CLAIM_PREFERRED_USERNAME = "preferred_username";
  private static final String CLAIM_AZP = "azp";

  /**
   * Roles CSS attaches to a token for the client it was issued to. Standard
   * Keycloak puts the same information under {@code resource_access.<client>.roles};
   * both are read, since which one appears depends on the realm's mappers.
   */
  private static final String CLAIM_CLIENT_ROLES = "client_roles";
  private static final String CLAIM_RESOURCE_ACCESS = "resource_access";
  private static final String CLAIM_EMAIL = "email";
  private static final String CLAIM_GIVEN_NAME = "given_name";
  private static final String CLAIM_FAMILY_NAME = "family_name";

  private static final String CLAIM_IDIR_USERNAME = "idir_username";
  private static final String CLAIM_IDIR_USER_GUID = "idir_user_guid";
  private static final String CLAIM_BCEID_USERNAME = "bceid_username";
  private static final String CLAIM_BCEID_USER_GUID = "bceid_user_guid";
  private static final String CLAIM_BCEID_BUSINESS_GUID = "bceid_business_guid";

  /**
   * The value stored in {@code fam_user.cognito_user_id}.
   *
   * <p>Keycloak's {@code preferred_username} is {@code <guid>@idir} or
   * {@code <guid>@bceidbusiness} - the same shape Cognito produced, and stable
   * per user per provider. The column keeps its old name because 50+ migrations
   * seed against it; see migrations/README.md.
   */
  public String oidcUserId(Jwt jwt) {
    String preferredUsername = jwt.getClaimAsString(CLAIM_PREFERRED_USERNAME);
    return preferredUsername != null ? preferredUsername : jwt.getSubject();
  }

  /**
   * The OIDC client the token was issued to.
   *
   * <p>Replaces Cognito's {@code callerContext.clientId}. It determines which
   * application a caller is acting within, so it decides which roles apply.
   */
  public String appClientId(Jwt jwt) {
    return jwt.getClaimAsString(CLAIM_AZP);
  }

  /**
   * The caller's roles for the client this token was issued to.
   *
   * <p>Under CSS these are the roles assigned in the integration, arriving as
   * {@code client_roles}. Falls back to {@code resource_access.<azp>.roles}, which
   * is where stock Keycloak puts them.
   *
   * <p>Scoping is inherent rather than enforced here: a token issued to a
   * downstream application carries only that application's roles, so FAM's own
   * admin roles cannot leak into it. That preserves the property the database
   * resolver got from its explicit "only when signing in through FAM" check.
   */
  @SuppressWarnings("unchecked")
  public List<String> accessRoles(Jwt jwt) {
    List<String> clientRoles = jwt.getClaimAsStringList(CLAIM_CLIENT_ROLES);
    if (clientRoles != null && !clientRoles.isEmpty()) {
      return List.copyOf(clientRoles);
    }

    Object resourceAccess = jwt.getClaim(CLAIM_RESOURCE_ACCESS);
    String clientId = appClientId(jwt);
    if (!(resourceAccess instanceof Map<?, ?> byClient) || clientId == null) {
      return List.of();
    }

    if (byClient.get(clientId) instanceof Map<?, ?> entry
        && entry.get("roles") instanceof List<?> roles) {
      return roles.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
    return List.of();
  }

  /**
   * Reports which claim was missing and what the token did carry.
   *
   * <p>Claim <em>names</em> only. The values are the user's name, email and GUID,
   * and a log is the wrong place for them - the names alone answer the question
   * this failure actually raises: is the claim absent, or present under a
   * different name than the realm's mappers were expected to produce?
   */
  private void logMissingClaim(Jwt jwt, IdentityProvider provider, String expectedClaim) {
    log.warn("Token from client '{}' (identity_provider={}) is missing '{}'. "
            + "Claims present: {}. The realm must map this claim into the ACCESS token, "
            + "not only the ID token - see backend/docs/authentication.md.",
        appClientId(jwt),
        provider.getClaimValue(),
        expectedClaim,
        jwt.getClaims().keySet().stream().sorted().toList());
  }

  /** Empty when the token came from a provider FAM does not support. */
  public Optional<IdentityProvider> identityProvider(Jwt jwt) {
    return IdentityProvider.fromClaim(jwt.getClaimAsString(CLAIM_IDENTITY_PROVIDER));
  }

  /**
   * Everything FAM needs about the caller, drawn from the provider-specific claims.
   *
   * @throws FamHttpException 403 when the provider is unsupported, or a claim FAM
   *     treats as a key is absent. Failing here is deliberate: a FAM identity
   *     without a GUID cannot be matched to a {@code fam_user} row and would
   *     silently create duplicates.
   */
  public TokenIdentity identity(Jwt jwt) {
    IdentityProvider provider = identityProvider(jwt).orElseThrow(() -> {
      log.warn("Token from client '{}' has identity_provider='{}', which FAM does not "
              + "support. Supported: {}. Claims present: {}.",
          appClientId(jwt),
          jwt.getClaimAsString(CLAIM_IDENTITY_PROVIDER),
          java.util.Arrays.stream(IdentityProvider.values())
              .map(IdentityProvider::getClaimValue).toList(),
          jwt.getClaims().keySet().stream().sorted().toList());
      return FamHttpException.forbidden(ErrorCode.EXTERNAL_USER_ACTION_PROHIBITED,
          "Identity provider '" + jwt.getClaimAsString(CLAIM_IDENTITY_PROVIDER)
              + "' is not supported by FAM.");
    });

    String userName = provider.isIdir()
        ? jwt.getClaimAsString(CLAIM_IDIR_USERNAME)
        : jwt.getClaimAsString(CLAIM_BCEID_USERNAME);

    String userGuid = provider.isIdir()
        ? jwt.getClaimAsString(CLAIM_IDIR_USER_GUID)
        : jwt.getClaimAsString(CLAIM_BCEID_USER_GUID);

    if (userGuid == null || userGuid.isBlank()) {
      logMissingClaim(jwt, provider, provider.isIdir() ? CLAIM_IDIR_USER_GUID
          : CLAIM_BCEID_USER_GUID);
      throw FamHttpException.forbidden(ErrorCode.MISSING_KEY_ATTRIBUTE,
          "Token is missing the user GUID claim for identity provider "
              + provider.getClaimValue() + ".");
    }
    if (userName == null || userName.isBlank()) {
      logMissingClaim(jwt, provider, provider.isIdir() ? CLAIM_IDIR_USERNAME
          : CLAIM_BCEID_USERNAME);
      throw FamHttpException.forbidden(ErrorCode.MISSING_KEY_ATTRIBUTE,
          "Token is missing the username claim for identity provider "
              + provider.getClaimValue() + ".");
    }

    return new TokenIdentity(
        provider,
        oidcUserId(jwt),
        userName,
        // FAM stores GUIDs upper-cased and without dashes, as Cognito supplied them.
        normalizeGuid(userGuid),
        provider.isIdir() ? null : normalizeGuid(
            jwt.getClaimAsString(CLAIM_BCEID_BUSINESS_GUID)),
        jwt.getClaimAsString(CLAIM_GIVEN_NAME),
        jwt.getClaimAsString(CLAIM_FAMILY_NAME),
        jwt.getClaimAsString(CLAIM_EMAIL));
  }

  /**
   * Keycloak may present a GUID dashed and lower-cased; FAM's stored values are
   * bare uppercase hex, and {@code fam_usr_uk} is matched on them.
   */
  private static String normalizeGuid(String guid) {
    if (guid == null || guid.isBlank()) {
      return null;
    }
    return guid.replace("-", "").toUpperCase(java.util.Locale.ROOT);
  }

  /** Identity as presented by the token, before it is reconciled with the database. */
  public record TokenIdentity(
      IdentityProvider identityProvider,
      String oidcUserId,
      String userName,
      String userGuid,
      String businessGuid,
      String firstName,
      String lastName,
      String email) {

    public String userTypeCode() {
      return identityProvider.getUserType().getCode();
    }
  }
}
