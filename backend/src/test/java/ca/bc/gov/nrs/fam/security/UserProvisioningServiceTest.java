package ca.bc.gov.nrs.fam.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.repository.FamUserRepository;
import ca.bc.gov.nrs.fam.security.TokenClaimsReader.TokenIdentity;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserProvisioningService (port of populate_user_if_necessary)")
class UserProvisioningServiceTest {

  private static final String GUID = "B5ECDB094DFB4149A6A8445A0MANGLED";

  @Mock private FamUserRepository userRepository;
  @InjectMocks private UserProvisioningService service;

  @BeforeEach
  void setUp() {
    when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  private static TokenIdentity idirIdentity() {
    return new TokenIdentity(IdentityProvider.IDIR, GUID.toLowerCase() + "@idir",
        "JSMITH", GUID, null, "Jane", "Smith", "jane@gov.bc.ca");
  }

  private static TokenIdentity bceidIdentity(String businessGuid) {
    return new TokenIdentity(IdentityProvider.BUSINESS_BCEID, "guid@bceidbusiness",
        "BUSER", GUID, businessGuid, "Bob", "Jones", "bob@acme.com");
  }

  @Test
  @DisplayName("creates a FAM user on first sign-in")
  void createsNewUser() {
    when(userRepository.findByUserTypeCodeAndUserGuidIgnoreCase(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(userRepository.findByUserTypeCodeAndUserNameIgnoreCase(anyString(), anyString()))
        .thenReturn(Optional.empty());

    FamUser user = service.provisionUser(idirIdentity());

    assertThat(user.getUserTypeCode()).isEqualTo(UserType.IDIR.getCode());
    assertThat(user.getUserGuid()).isEqualTo(GUID);
    assertThat(user.getUserName()).isEqualTo("JSMITH");
    assertThat(user.getEmail()).isEqualTo("jane@gov.bc.ca");
    // Written by the login flow, not by an administrator.
    assertThat(user.getCreateUser()).isEqualTo(FamConstants.SYSTEM_ACCOUNT_NAME);
  }

  @Test
  @DisplayName("adopts a pre-V50 row that has no GUID rather than duplicating the user")
  void adoptsLegacyRowWithoutGuid() {
    FamUser legacy = new FamUser();
    legacy.setUserId(42L);
    legacy.setUserTypeCode(UserType.IDIR.getCode());
    legacy.setUserName("JSMITH");
    legacy.setUserGuid(null);

    when(userRepository.findByUserTypeCodeAndUserGuidIgnoreCase(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(userRepository.findByUserTypeCodeAndUserNameIgnoreCase(anyString(), anyString()))
        .thenReturn(Optional.of(legacy));

    FamUser user = service.provisionUser(idirIdentity());

    assertThat(user.getUserId()).isEqualTo(42L);
    assertThat(user.getUserGuid()).isEqualTo(GUID);
  }

  @Test
  @DisplayName("creates a new row when a name matches but the GUID differs")
  void nameCollisionWithDifferentGuidCreatesNewUser() {
    // Identity providers reuse usernames, so a name match with a different GUID
    // is a different person.
    FamUser someoneElse = new FamUser();
    someoneElse.setUserId(42L);
    someoneElse.setUserName("JSMITH");
    someoneElse.setUserGuid("DIFFERENTGUID000000000000000000A");

    when(userRepository.findByUserTypeCodeAndUserGuidIgnoreCase(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(userRepository.findByUserTypeCodeAndUserNameIgnoreCase(anyString(), anyString()))
        .thenReturn(Optional.of(someoneElse));

    FamUser user = service.provisionUser(idirIdentity());

    assertThat(user.getUserId()).isNull();
    assertThat(user.getUserGuid()).isEqualTo(GUID);
  }

  @Test
  @DisplayName("refreshes a returning user's name and email")
  void refreshesExistingUserDetails() {
    FamUser existing = new FamUser();
    existing.setUserId(7L);
    existing.setUserTypeCode(UserType.IDIR.getCode());
    existing.setUserGuid(GUID);
    existing.setUserName("OLD_NAME");
    existing.setEmail("old@gov.bc.ca");

    when(userRepository.findByUserTypeCodeAndUserGuidIgnoreCase(anyString(), anyString()))
        .thenReturn(Optional.of(existing));

    FamUser user = service.provisionUser(idirIdentity());

    assertThat(user.getUserName()).isEqualTo("JSMITH");
    assertThat(user.getEmail()).isEqualTo("jane@gov.bc.ca");
    assertThat(user.getUpdateUser()).isEqualTo(FamConstants.SYSTEM_ACCOUNT_NAME);
  }

  @Test
  @DisplayName("stores the organisation for a Business BCeID user")
  void storesBusinessGuid() {
    when(userRepository.findByUserTypeCodeAndUserGuidIgnoreCase(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(userRepository.findByUserTypeCodeAndUserNameIgnoreCase(anyString(), anyString()))
        .thenReturn(Optional.empty());

    FamUser user = service.provisionUser(bceidIdentity("000000000000000000000000000000AA"));

    assertThat(user.getBusinessGuid()).isEqualTo("000000000000000000000000000000AA");
  }

  @Test
  @DisplayName("keeps a stored organisation when the token does not carry one")
  void doesNotBlankBusinessGuid() {
    // A token missing bceid_business_guid must not wipe an organisation the
    // same-organisation rules depend on.
    FamUser existing = new FamUser();
    existing.setUserId(7L);
    existing.setUserGuid(GUID);
    existing.setBusinessGuid("000000000000000000000000000000AA");

    when(userRepository.findByUserTypeCodeAndUserGuidIgnoreCase(anyString(), anyString()))
        .thenReturn(Optional.of(existing));

    FamUser user = service.provisionUser(bceidIdentity(null));

    assertThat(user.getBusinessGuid()).isEqualTo("000000000000000000000000000000AA");
  }

  @Test
  @DisplayName("stores the Keycloak subject in the OIDC user id column")
  void storesOidcUserId() {
    when(userRepository.findByUserTypeCodeAndUserGuidIgnoreCase(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(userRepository.findByUserTypeCodeAndUserNameIgnoreCase(anyString(), anyString()))
        .thenReturn(Optional.empty());

    assertThat(service.provisionUser(idirIdentity()).getOidcUserId())
        .isEqualTo(GUID.toLowerCase() + "@idir");
  }
}
