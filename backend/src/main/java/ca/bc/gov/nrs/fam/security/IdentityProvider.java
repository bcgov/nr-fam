package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.constants.UserType;
import java.util.Locale;
import java.util.Optional;

/**
 * BC Gov SSO (Keycloak) identity providers, and how they map onto FAM's
 * {@code fam_user_type_code}.
 *
 * <p>Replaces the {@code custom:idp_name} attribute Cognito carried. Keycloak
 * publishes the provider in the {@code identity_provider} claim.
 *
 * <p>Deliberately an allowlist. Basic BCeID and the other providers a BC Gov realm
 * can expose are <em>not</em> mapped: FAM only ever supported IDIR and Business
 * BCeID, and silently admitting a Basic BCeID user would create a FAM identity
 * with no organisation and no way to apply the same-organisation rules.
 */
public enum IdentityProvider {

  /** Legacy SiteMinder IDIR. */
  IDIR("idir", UserType.IDIR),

  /** Entra-backed IDIR. Same FAM user type; the realm distinguishes them, FAM does not. */
  AZURE_IDIR("azureidir", UserType.IDIR),

  BUSINESS_BCEID("bceidbusiness", UserType.BCEID);

  private final String claimValue;
  private final UserType userType;

  IdentityProvider(String claimValue, UserType userType) {
    this.claimValue = claimValue;
    this.userType = userType;
  }

  public String getClaimValue() {
    return claimValue;
  }

  public UserType getUserType() {
    return userType;
  }

  public boolean isIdir() {
    return userType == UserType.IDIR;
  }

  /**
   * @return empty for any provider FAM does not support, including
   *     {@code bceidbasic}, {@code bceidboth} and BC Services Card
   */
  public static Optional<IdentityProvider> fromClaim(String identityProvider) {
    if (identityProvider == null) {
      return Optional.empty();
    }
    String normalized = identityProvider.trim().toLowerCase(Locale.ROOT);
    for (IdentityProvider provider : values()) {
      if (provider.claimValue.equals(normalized)) {
        return Optional.of(provider);
      }
    }
    return Optional.empty();
  }
}
