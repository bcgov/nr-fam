package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.FamUserUpdateResponse;
import ca.bc.gov.nrs.fam.dto.UserLookupBceidUserDto;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import ca.bc.gov.nrs.fam.repository.FamUserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserInfoRefreshService (port of update_user_info_from_idim_source)")
class UserInfoRefreshServiceTest {

  private static final String GUID_A = "A".repeat(32);
  private static final String GUID_B = "B".repeat(32);

  @Mock private FamUserRepository userRepository;
  @Mock private UserLookupClient userLookupClient;

  private UserInfoRefreshService service;

  @BeforeEach
  void setUp() {
    FamProperties properties = new FamProperties("dev", null, null,
        new FamProperties.UpdateUserInfo("secret", "CMENG"));
    service = new UserInfoRefreshService(userRepository, userLookupClient, properties);

    FamUser requester = user(1L, "CMENG", UserType.IDIR, GUID_B);
    when(userRepository.findByUserTypeCodeAndUserNameIgnoreCase(
        UserType.IDIR.getCode(), "CMENG")).thenReturn(Optional.of(requester));
    when(userRepository.count()).thenReturn(1L);
    when(userRepository.findAll()).thenReturn(List.of());
    when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
  }

  private static FamUser user(Long id, String name, UserType type, String guid) {
    FamUser user = new FamUser();
    user.setUserId(id);
    user.setUserName(name);
    user.setUserTypeCode(type.getCode());
    user.setUserGuid(guid);
    return user;
  }

  private FamUserUpdateResponse run(List<FamUser> users) {
    when(userRepository.findAllByUserTypeCodeIn(any())).thenReturn(users);
    return service.refreshFromDirectory(false, 1, 100);
  }

  @Test
  @DisplayName("refreshes an IDIR user's name and email")
  void refreshesIdirUser() {
    FamUser target = user(2L, "JSMITH", UserType.IDIR, GUID_A);
    when(userLookupClient.getIdirDetail(eq("JSMITH")))
        .thenReturn(Optional.of(new UserLookupIdirUserDto(
            true, "JSMITH", GUID_A, "Jane", "Smith", "jane@gov.bc.ca")));

    FamUserUpdateResponse response = run(List.of(target));

    assertThat(response.successUserUpdateList()).hasSize(1);
    assertThat(target.getFirstName()).isEqualTo("Jane");
    assertThat(target.getEmail()).isEqualTo("jane@gov.bc.ca");
  }

  @Test
  @DisplayName("skips a user whose stored GUID disagrees with IDIM, rather than overwriting")
  void skipsGuidMismatch() {
    // The user name was reassigned to somebody else. Writing the new person's
    // details onto this FAM identity would silently transfer their access.
    FamUser target = user(2L, "JSMITH", UserType.IDIR, GUID_A);
    when(userLookupClient.getIdirDetail(eq("JSMITH")))
        .thenReturn(Optional.of(new UserLookupIdirUserDto(
            true, "JSMITH", GUID_B, "Someone", "Else", "someone@gov.bc.ca")));

    FamUserUpdateResponse response = run(List.of(target));

    assertThat(response.mismatchUserUpdateList()).hasSize(1);
    assertThat(response.successUserUpdateList()).isEmpty();
    assertThat(target.getUserGuid()).isEqualTo(GUID_A);
    verify(userRepository, never()).save(any());
  }

  @Test
  @DisplayName("records a user IDIM cannot find as failed")
  void recordsNotFoundAsFailed() {
    FamUser target = user(2L, "GHOST", UserType.IDIR, GUID_A);
    when(userLookupClient.getIdirDetail(anyString()))
        .thenReturn(Optional.empty());

    FamUserUpdateResponse response = run(List.of(target));

    assertThat(response.failedUserUpdateList()).hasSize(1);
    assertThat(response.successUserUpdateList()).isEmpty();
  }

  @Test
  @DisplayName("looks a BCeID user up by GUID when one is stored, and refreshes their name")
  void bceidLookupByGuid() {
    FamUser target = user(2L, "OLD_NAME", UserType.BCEID, GUID_A);
    when(userLookupClient.getBusinessBceid(
        eq(UserLookupClient.SearchBy.USER_GUID), eq(GUID_A)))
        .thenReturn(Optional.of(new UserLookupBceidUserDto(
            true, "NEW_NAME", GUID_A, "ORG", "Acme", "Bob", "Jones", "bob@acme.com")));

    run(List.of(target));

    assertThat(target.getUserName()).isEqualTo("NEW_NAME");
    assertThat(target.getBusinessGuid()).isEqualTo("ORG");
  }

  @Test
  @DisplayName("looks a BCeID user up by name when no GUID is stored, and back-fills it")
  void bceidLookupByNameBackfillsGuid() {
    FamUser target = user(2L, "BUSER", UserType.BCEID, null);
    when(userLookupClient.getBusinessBceid(
        eq(UserLookupClient.SearchBy.USER_ID), eq("BUSER")))
        .thenReturn(Optional.of(new UserLookupBceidUserDto(
            true, "BUSER", GUID_A, "ORG", "Acme",
            "Bob", "Jones", "bob@acme.com")));

    run(List.of(target));

    assertThat(target.getUserGuid()).isEqualTo(GUID_A);
  }

  @Test
  @DisplayName("does not blank a stored field when IDIM returns nothing for it")
  void doesNotBlankStoredFields() {
    FamUser target = user(2L, "JSMITH", UserType.IDIR, GUID_A);
    target.setEmail("kept@gov.bc.ca");
    when(userLookupClient.getIdirDetail(anyString()))
        .thenReturn(Optional.of(new UserLookupIdirUserDto(
            true, "JSMITH", GUID_A, "Jane", "Smith", null)));

    run(List.of(target));

    assertThat(target.getEmail()).isEqualTo("kept@gov.bc.ca");
  }

  @Test
  @DisplayName("one failing user does not abort the sweep")
  void oneFailureDoesNotAbortTheRun() {
    FamUser bad = user(2L, "BOOM", UserType.IDIR, GUID_A);
    FamUser good = user(3L, "JSMITH", UserType.IDIR, GUID_A);
    when(userLookupClient.getIdirDetail(eq("BOOM")))
        .thenThrow(new RuntimeException("the directory exploded"));
    when(userLookupClient.getIdirDetail(eq("JSMITH")))
        .thenReturn(Optional.of(new UserLookupIdirUserDto(
            true, "JSMITH", GUID_A, "Jane", "Smith", "jane@gov.bc.ca")));

    FamUserUpdateResponse response = run(List.of(bad, good));

    assertThat(response.failedUserUpdateList()).hasSize(1);
    assertThat(response.successUserUpdateList()).hasSize(1);
  }

  @Test
  @DisplayName("reports BC Services Card users as ignored, not dropped")
  void reportsBcscUsersAsIgnored() {
    // IDIM holds no data for them, but the report must still account for them.
    when(userRepository.findAll())
        .thenReturn(List.of(user(9L, "BCSCUSER", UserType.BCSC_PROD, GUID_A)));

    FamUserUpdateResponse response = run(List.of());

    assertThat(response.ignoredUserUpdateList()).hasSize(1);
  }

  @Test
  @DisplayName("no longer needs a requester row to run")
  void doesNotNeedARequester() {
    // IDIM audited every lookup against a real fam_user row, so the refresh used
    // to fail without one. The directory authenticates as a service account and
    // takes no requester, so that requirement is gone.
    when(userRepository.findByUserTypeCodeAndUserNameIgnoreCase(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(userRepository.findAllByUserTypeCodeIn(any())).thenReturn(List.of());

    assertThatCode(() -> service.refreshFromDirectory(false, 1, 100))
        .doesNotThrowAnyException();
  }
}
