package ca.bc.gov.nrs.fam.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.DirectoryEnv;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.UserLookupBceidUserDto;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

/**
 * A Business BCeID administrator may only grant within their own organisation.
 *
 * <p>The rule that matters is that the target's organisation comes from the
 * directory rather than from the request - a caller who could assert their
 * target's organisation could assert their way past this.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TargetOrganizationGuard")
class TargetOrganizationGuardTest {

  private static final String OWN_ORG = "AAAA0000BUSINESS";
  private static final String OTHER_ORG = "BBBB1111BUSINESS";
  private static final String TARGET_GUID = "CCCC2222USER";

  @Mock private UserLookupClient userLookupClient;

  private final AuthorizationService authorizationService =
      new AuthorizationService(new FamProperties("dev", null, null));

  private TargetOrganizationGuard guard;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    guard = new TargetOrganizationGuard(userLookupClient, authorizationService);
  }

  private static Requester bceidAdmin(String businessGuid) {
    return Requester.builder()
        .userName("BCEID_ADMIN").userGuid("REQUESTER1").userType(UserType.BCEID)
        .businessGuid(businessGuid).accessRoles(List.of("DELEGATED_ADMIN_1_DEV"))
        .build();
  }

  private static Requester idirAdmin() {
    return Requester.builder()
        .userName("JSMITH").userGuid("REQUESTER2").userType(UserType.IDIR)
        .accessRoles(List.of("FAM_ADMIN")).build();
  }

  private void directoryReports(String businessGuid) {
    when(userLookupClient.getBusinessBceid(any(), any(), anyString())).thenReturn(
        Optional.of(new UserLookupBceidUserDto(
            true, "TARGET", TARGET_GUID, businessGuid, "Example Forestry Ltd",
            "Jane", "Smith", "jane@example.com")));
  }

  @Test
  @DisplayName("allows a grant within the administrator's own organisation")
  void allowsSameOrganization() {
    directoryReports(OWN_ORG);

    assertThatCode(() -> guard.requireSameOrganization(
        bceidAdmin(OWN_ORG), DirectoryEnv.TEST, UserType.BCEID, TARGET_GUID))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses a grant to another organisation")
  void refusesOtherOrganization() {
    // The hole this closes: the administrator cannot find such a user through
    // FAM, but could name one directly by GUID.
    directoryReports(OTHER_ORG);

    assertThatThrownBy(() -> guard.requireSameOrganization(
        bceidAdmin(OWN_ORG), DirectoryEnv.TEST, UserType.BCEID, TARGET_GUID))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("same organization");
  }

  @Test
  @DisplayName("compares organisations regardless of case")
  void comparesCaseInsensitively() {
    directoryReports(OWN_ORG.toLowerCase());

    assertThatCode(() -> guard.requireSameOrganization(
        bceidAdmin(OWN_ORG), DirectoryEnv.TEST, UserType.BCEID, TARGET_GUID))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("takes the organisation from the directory, not from the caller")
  void readsOrganizationFromTheDirectory() {
    // The request carries a GUID and a user type and nothing else about the
    // target - both are claims about somebody else, so the organisation has to
    // be looked up.
    directoryReports(OTHER_ORG);

    assertThatThrownBy(() -> guard.requireSameOrganization(
        bceidAdmin(OWN_ORG), DirectoryEnv.TEST, UserType.BCEID, TARGET_GUID))
        .isInstanceOf(FamHttpException.class);

    verify(userLookupClient).getBusinessBceid(
        DirectoryEnv.TEST, UserLookupClient.SearchBy.USER_GUID, TARGET_GUID);
  }

  @Test
  @DisplayName("refuses a BCeID administrator granting to an IDIR user")
  void refusesIdirTarget() {
    // An IDIR user belongs to no business, so there is no organisation that
    // could match. Upstream excluded them from a BCeID administrator's view too.
    assertThatThrownBy(() -> guard.requireSameOrganization(
        bceidAdmin(OWN_ORG), DirectoryEnv.TEST, UserType.IDIR, TARGET_GUID))
        .isInstanceOf(FamHttpException.class);

    verify(userLookupClient, never()).getBusinessBceid(any(), any(), anyString());
  }

  @Test
  @DisplayName("refuses an unknown user the same way as a foreign one")
  void unknownUserIsRefusedIdentically() {
    // Distinguishing them would report whether an account exists at another
    // organisation, which is what the rule exists to prevent.
    when(userLookupClient.getBusinessBceid(any(), any(), anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> guard.requireSameOrganization(
        bceidAdmin(OWN_ORG), DirectoryEnv.TEST, UserType.BCEID, "NOBODY"))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("same organization");
  }

  @Test
  @DisplayName("refuses when the target has no organisation recorded")
  void refusesTargetWithNoOrganization() {
    // An unknown organisation is not a matching one.
    directoryReports(null);

    assertThatThrownBy(() -> guard.requireSameOrganization(
        bceidAdmin(OWN_ORG), DirectoryEnv.TEST, UserType.BCEID, TARGET_GUID))
        .isInstanceOf(FamHttpException.class);
  }

  @Test
  @DisplayName("refuses when the administrator has no organisation recorded")
  void refusesRequesterWithNoOrganization() {
    directoryReports(OWN_ORG);

    assertThatThrownBy(() -> guard.requireSameOrganization(
        bceidAdmin(null), DirectoryEnv.TEST, UserType.BCEID, TARGET_GUID))
        .isInstanceOf(FamHttpException.class);
  }

  @Test
  @DisplayName("fails closed when the directory cannot be reached")
  void failsClosedOnDirectoryFailure() {
    // An unverifiable organisation is not a matching one, so the grant must not
    // proceed.
    when(userLookupClient.getBusinessBceid(any(), any(), anyString()))
        .thenThrow(new UpstreamException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout",
            "timed out", "user-lookup-api"));

    assertThatThrownBy(() -> guard.requireSameOrganization(
        bceidAdmin(OWN_ORG), DirectoryEnv.TEST, UserType.BCEID, TARGET_GUID))
        .isInstanceOf(UpstreamException.class);
  }

  @Test
  @DisplayName("leaves an IDIR administrator unrestricted")
  void idirAdministratorIsUnrestricted() {
    // They administer across organisations by definition, so there is nothing to
    // compare and no call to make.
    assertThatCode(() -> guard.requireSameOrganization(
        idirAdmin(), DirectoryEnv.TEST, UserType.BCEID, TARGET_GUID))
        .doesNotThrowAnyException();

    verify(userLookupClient, never()).getBusinessBceid(any(), any(), anyString());
  }

  @Test
  @DisplayName("leaves a system grant alone")
  void systemGrantIsUnrestricted() {
    // No requester means no organisation to be outside of.
    assertThatCode(() -> guard.requireSameOrganization(null, DirectoryEnv.TEST, UserType.BCEID, TARGET_GUID))
        .doesNotThrowAnyException();

    verify(userLookupClient, never()).getBusinessBceid(any(), any(), anyString());
  }
}
