package ca.bc.gov.nrs.fam.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Claim extraction for BC Gov SSO (Keycloak).
 *
 * <p>Claim names follow the Identity Mappers reference in
 * https://github.com/bcgov/sso-docs. Getting one wrong silently produces a
 * mis-identified user, so each provider's mapping is pinned here.
 */
@DisplayName("TokenClaimsReader (Cognito -> Keycloak claim seam)")
class TokenClaimsReaderTest {

  private final TokenClaimsReader reader = new TokenClaimsReader();

  private static Jwt jwt(Map<String, Object> claims) {
    Map<String, Object> withSub = new HashMap<>(claims);
    withSub.putIfAbsent("sub", "keycloak-sub");
    return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300),
        Map.of("alg", "RS256"), withSub);
  }

  private static Map<String, Object> idirClaims() {
    Map<String, Object> claims = new HashMap<>();
    claims.put("identity_provider", "idir");
    claims.put("preferred_username", "b5ecdb094dfb4149a6a8445a0mangled@idir");
    claims.put("idir_username", "JSMITH");
    claims.put("idir_user_guid", "B5ECDB094DFB4149A6A8445A0MANGLED");
    claims.put("given_name", "Jane");
    claims.put("family_name", "Smith");
    claims.put("email", "jane.smith@gov.bc.ca");
    claims.put("azp", "fam-console");
    return claims;
  }

  private static Map<String, Object> bceidClaims() {
    Map<String, Object> claims = new HashMap<>();
    claims.put("identity_provider", "bceidbusiness");
    claims.put("preferred_username", "bda2a1e212244dc2b9f9522057mangled@bceidbusiness");
    claims.put("bceid_username", "BUSER");
    claims.put("bceid_user_guid", "BDA2A1E212244DC2B9F9522057MANGLED");
    claims.put("bceid_business_guid", "000000000000000000000000000000AA");
    claims.put("email", "bob@acme.com");
    claims.put("azp", "fam-console");
    return claims;
  }

  @Nested
  @DisplayName("identity extraction")
  class IdentityExtraction {

    @Test
    @DisplayName("reads IDIR identity from idir_* claims")
    void readsIdirIdentity() {
      var identity = reader.identity(jwt(idirClaims()));

      assertThat(identity.identityProvider()).isEqualTo(IdentityProvider.IDIR);
      assertThat(identity.userTypeCode()).isEqualTo(UserType.IDIR.getCode());
      assertThat(identity.userName()).isEqualTo("JSMITH");
      assertThat(identity.userGuid()).isEqualTo("B5ECDB094DFB4149A6A8445A0MANGLED");
      assertThat(identity.firstName()).isEqualTo("Jane");
      assertThat(identity.email()).isEqualTo("jane.smith@gov.bc.ca");
      // IDIR users have no organisation.
      assertThat(identity.businessGuid()).isNull();
    }

    @Test
    @DisplayName("reads Business BCeID identity from bceid_* claims, including the organisation")
    void readsBceidIdentity() {
      var identity = reader.identity(jwt(bceidClaims()));

      assertThat(identity.identityProvider()).isEqualTo(IdentityProvider.BUSINESS_BCEID);
      assertThat(identity.userTypeCode()).isEqualTo(UserType.BCEID.getCode());
      assertThat(identity.userName()).isEqualTo("BUSER");
      assertThat(identity.userGuid()).isEqualTo("BDA2A1E212244DC2B9F9522057MANGLED");
      assertThat(identity.businessGuid()).isEqualTo("000000000000000000000000000000AA");
    }

    @Test
    @DisplayName("treats Entra IDIR as IDIR")
    void azureIdirMapsToIdir() {
      Map<String, Object> claims = idirClaims();
      claims.put("identity_provider", "azureidir");

      var identity = reader.identity(jwt(claims));

      assertThat(identity.identityProvider()).isEqualTo(IdentityProvider.AZURE_IDIR);
      assertThat(identity.userTypeCode()).isEqualTo(UserType.IDIR.getCode());
    }

    @Test
    @DisplayName("normalises a dashed, lower-cased GUID to FAM's stored form")
    void normalisesGuid() {
      // FAM stores bare uppercase hex, and fam_usr_uk is matched on it.
      Map<String, Object> claims = idirClaims();
      claims.put("idir_user_guid", "b5ecdb09-4dfb-4149-a6a8-445a0mangled");

      assertThat(reader.identity(jwt(claims)).userGuid())
          .isEqualTo("B5ECDB094DFB4149A6A8445A0MANGLED");
    }
  }

  @Nested
  @DisplayName("unsupported providers")
  class UnsupportedProviders {

    @ParameterizedTest
    @ValueSource(strings = {"bceidbasic", "bceidboth", "githubbcgov", "digitalcredential"})
    @DisplayName("rejects providers FAM never supported")
    void rejectsUnsupportedProvider(String provider) {
      // Admitting a Basic BCeID user would create a FAM identity with no
      // organisation, which the same-organisation rules cannot reason about.
      Map<String, Object> claims = idirClaims();
      claims.put("identity_provider", provider);

      assertThatThrownBy(() -> reader.identity(jwt(claims)))
          .isInstanceOf(FamHttpException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.EXTERNAL_USER_ACTION_PROHIBITED);
    }

    @Test
    @DisplayName("rejects a token with no identity provider claim")
    void rejectsMissingProvider() {
      Map<String, Object> claims = idirClaims();
      claims.remove("identity_provider");

      assertThatThrownBy(() -> reader.identity(jwt(claims)))
          .isInstanceOf(FamHttpException.class);
    }
  }

  @Nested
  @DisplayName("missing key claims")
  class MissingKeyClaims {

    @ParameterizedTest
    @CsvSource({"idir_user_guid", "idir_username"})
    @DisplayName("rejects a token missing a claim FAM keys on")
    void rejectsMissingKeyClaim(String claimToRemove) {
      // A FAM identity without a GUID cannot be matched to a fam_user row and
      // would silently create duplicates.
      Map<String, Object> claims = idirClaims();
      claims.remove(claimToRemove);

      assertThatThrownBy(() -> reader.identity(jwt(claims)))
          .isInstanceOf(FamHttpException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.MISSING_KEY_ATTRIBUTE);
    }

    @Test
    @DisplayName("rejects a blank GUID as firmly as an absent one")
    void rejectsBlankGuid() {
      Map<String, Object> claims = idirClaims();
      claims.put("idir_user_guid", "   ");

      assertThatThrownBy(() -> reader.identity(jwt(claims)))
          .isInstanceOf(FamHttpException.class);
    }
  }

  @Nested
  @DisplayName("other claims")
  class OtherClaims {

    @Test
    @DisplayName("uses preferred_username as the stored OIDC subject")
    void usesPreferredUsername() {
      // Keycloak's <guid>@idir has the same shape Cognito produced, which is what
      // the cognito_user_id column holds.
      assertThat(reader.oidcUserId(jwt(idirClaims())))
          .isEqualTo("b5ecdb094dfb4149a6a8445a0mangled@idir");
    }

    @Test
    @DisplayName("falls back to sub when preferred_username is absent")
    void fallsBackToSub() {
      Map<String, Object> claims = idirClaims();
      claims.remove("preferred_username");

      assertThat(reader.oidcUserId(jwt(claims))).isEqualTo("keycloak-sub");
    }

    @Test
    @DisplayName("reads the calling client from azp")
    void readsAuthorizedParty() {
      assertThat(reader.appClientId(jwt(idirClaims()))).isEqualTo("fam-console");
    }
  }

  @Test
  @DisplayName("names the missing claim and the provider when a key claim is absent")
  void reportsMissingClaim() {
    // The realm maps these; when one does not reach the ACCESS token the failure
    // has to say which, or the only signal is a bare error code.
    Map<String, Object> claims = new HashMap<>();
    claims.put("identity_provider", "azureidir");
    claims.put("idir_username", "JSMITH");
    claims.put("azp", "forests-access-management-22261");
    // idir_user_guid deliberately absent.

    assertThatThrownBy(() -> reader.identity(jwt(claims)))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("user GUID")
        .hasMessageContaining("azureidir");
  }

  @Test
  @DisplayName("a token with the GUID but no username fails on the username")
  void reportsMissingUsername() {
    Map<String, Object> claims = new HashMap<>();
    claims.put("identity_provider", "azureidir");
    claims.put("idir_user_guid", "1122334455667788AABBCCDDEEFF0011");
    claims.put("azp", "forests-access-management-22261");

    assertThatThrownBy(() -> reader.identity(jwt(claims)))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("username");
  }
}
