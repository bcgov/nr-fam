package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.IdimSearchUserParamType;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.IdimProxyBceidInfoDto;
import ca.bc.gov.nrs.fam.dto.IdimProxyIdirInfoDto;
import ca.bc.gov.nrs.fam.dto.TargetUser;
import ca.bc.gov.nrs.fam.dto.TargetUserValidationResult;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.IdimProxyService;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("TargetUserValidationService (port of target_user_validator.py)")
class TargetUserValidationServiceTest {

  private static final String GUID_A = "A".repeat(32);
  private static final String GUID_B = "B".repeat(32);
  private static final String ORG_1 = "1".repeat(32);
  private static final String ORG_2 = "2".repeat(32);

  @Mock
  private IdimProxyService idimProxyService;

  @Mock
  private ApiInstanceEnvResolver apiInstanceEnvResolver;

  @InjectMocks
  private TargetUserValidationService service;

  private static Requester requester(UserType type, String businessGuid) {
    return Requester.builder()
        .userId(1L).userName("ADMIN").userType(type)
        .userGuid(GUID_B).businessGuid(businessGuid).build();
  }

  private static TargetUser targetUser(String userName, String guid, UserType type) {
    return TargetUser.builder()
        .userName(userName).userGuid(guid).userTypeCode(type.getCode()).build();
  }

  private static FamRole role() {
    FamApplication application = new FamApplication();
    application.setApplicationId(1L);
    application.setApplicationName("FOM_DEV");
    application.setAppEnvironment("DEV");

    FamRole role = new FamRole();
    role.setRoleId(10L);
    role.setApplication(application);
    return role;
  }

  @Nested
  @DisplayName("IDIR verification")
  class IdirVerification {

    @Test
    @DisplayName("enriches the target user from IDIM")
    void enrichesFromIdim() {
      when(idimProxyService.lookupIdir(eq("JSMITH"), any(), any()))
          .thenReturn(new IdimProxyIdirInfoDto(
              true, "JSMITH", GUID_A, "Jane", "Smith", "jane@gov.bc.ca"));

      TargetUser verified = service.verifyUserExists(
          requester(UserType.IDIR, null), targetUser("JSMITH", GUID_A, UserType.IDIR),
          ApiInstanceEnv.TEST);

      assertThat(verified.firstName()).isEqualTo("Jane");
      assertThat(verified.lastName()).isEqualTo("Smith");
      assertThat(verified.email()).isEqualTo("jane@gov.bc.ca");
    }

    @Test
    @DisplayName("rejects a name paired with someone else's GUID")
    void rejectsGuidMismatch() {
      // Only reachable by calling the API directly; the UI cannot produce it.
      when(idimProxyService.lookupIdir(eq("JSMITH"), any(), any()))
          .thenReturn(new IdimProxyIdirInfoDto(
              true, "JSMITH", GUID_A, "Jane", "Smith", "jane@gov.bc.ca"));

      assertThatThrownBy(() -> service.verifyUserExists(
          requester(UserType.IDIR, null), targetUser("JSMITH", GUID_B, UserType.IDIR),
          ApiInstanceEnv.TEST))
          .isInstanceOf(FamHttpException.class)
          .hasMessageContaining("does not match the user guid in request");
    }

    @Test
    @DisplayName("rejects a user IDIM does not know")
    void rejectsUnknownUser() {
      when(idimProxyService.lookupIdir(any(), any(), any()))
          .thenReturn(new IdimProxyIdirInfoDto(false, "GHOST", null, null, null, null));

      assertThatThrownBy(() -> service.verifyUserExists(
          requester(UserType.IDIR, null), targetUser("GHOST", GUID_A, UserType.IDIR),
          ApiInstanceEnv.TEST))
          .isInstanceOf(FamHttpException.class)
          .hasMessageContaining("cannot find user");
    }
  }

  @Nested
  @DisplayName("BCeID verification")
  class BceidVerification {

    @Test
    @DisplayName("looks up by GUID and adopts the organisation IDIM reports")
    void adoptsBusinessGuid() {
      when(idimProxyService.lookupBusinessBceid(
          eq(IdimSearchUserParamType.USER_GUID), eq(GUID_A), any(), any()))
          .thenReturn(new IdimProxyBceidInfoDto(
              true, "BUSER", GUID_A, ORG_1, "Acme", "Bob", "Jones", "bob@acme.com"));

      TargetUser verified = service.verifyUserExists(
          requester(UserType.BCEID, ORG_1), targetUser("BUSER", GUID_A, UserType.BCEID),
          ApiInstanceEnv.TEST);

      assertThat(verified.businessGuid()).isEqualTo(ORG_1);
      assertThat(verified.email()).isEqualTo("bob@acme.com");
    }

    @Test
    @DisplayName("compares user names case-insensitively, since BCeID ids are not case-stable")
    void userNameComparisonIsCaseInsensitive() {
      when(idimProxyService.lookupBusinessBceid(any(), any(), any(), any()))
          .thenReturn(new IdimProxyBceidInfoDto(
              true, "BUser", GUID_A, ORG_1, "Acme", "Bob", "Jones", "bob@acme.com"));

      assertThatCode(() -> service.verifyUserExists(
          requester(UserType.BCEID, ORG_1), targetUser("buser", GUID_A, UserType.BCEID),
          ApiInstanceEnv.TEST))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a GUID paired with the wrong user name")
    void rejectsUserNameMismatch() {
      when(idimProxyService.lookupBusinessBceid(any(), any(), any(), any()))
          .thenReturn(new IdimProxyBceidInfoDto(
              true, "SOMEONE_ELSE", GUID_A, ORG_1, "Acme", "Bob", "Jones", "bob@acme.com"));

      assertThatThrownBy(() -> service.verifyUserExists(
          requester(UserType.BCEID, ORG_1), targetUser("BUSER", GUID_A, UserType.BCEID),
          ApiInstanceEnv.TEST))
          .isInstanceOf(FamHttpException.class)
          .hasMessageContaining("does not match the username in request");
    }
  }

  @Test
  @DisplayName("rejects an unsupported identity type")
  void rejectsUnsupportedUserType() {
    assertThatThrownBy(() -> service.verifyUserExists(
        requester(UserType.IDIR, null), targetUser("X", GUID_A, UserType.BCSC_PROD),
        ApiInstanceEnv.TEST))
        .isInstanceOf(FamHttpException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER);
  }

  @Test
  @DisplayName("collects failures per user rather than aborting the batch")
  void batchCollectsFailures() {
    when(apiInstanceEnvResolver.resolve(any())).thenReturn(ApiInstanceEnv.TEST);
    when(idimProxyService.lookupIdir(eq("GOOD"), any(), any()))
        .thenReturn(new IdimProxyIdirInfoDto(true, "GOOD", GUID_A, "G", "Ood", "g@gov.bc.ca"));
    when(idimProxyService.lookupIdir(eq("GHOST"), any(), any()))
        .thenReturn(new IdimProxyIdirInfoDto(false, "GHOST", null, null, null, null));

    TargetUserValidationResult result = service.validateTargetUsers(
        requester(UserType.IDIR, null),
        List.of(targetUser("GOOD", GUID_A, UserType.IDIR),
            targetUser("GHOST", GUID_A, UserType.IDIR)),
        role());

    assertThat(result.verifiedUsers()).singleElement()
        .extracting(TargetUser::userName).isEqualTo("GOOD");
    assertThat(result.failedUsers()).singleElement()
        .extracting(u -> u.userName()).isEqualTo("GHOST");
  }

  @Nested
  @DisplayName("same-organisation rule")
  class SameOrgRule {

    @Test
    @DisplayName("does not constrain an IDIR requester")
    void idirRequesterUnconstrained() {
      assertThatCode(() -> service.validateBceidSameOrg(
          requester(UserType.IDIR, null),
          List.of(targetUser("X", GUID_A, UserType.BCEID).toBuilder()
              .businessGuid(ORG_2).build())))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("allows a BCeID requester within their own organisation")
    void allowsSameOrg() {
      assertThatCode(() -> service.validateBceidSameOrg(
          requester(UserType.BCEID, ORG_1),
          List.of(targetUser("X", GUID_A, UserType.BCEID).toBuilder()
              .businessGuid(ORG_1).build())))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("blocks a BCeID requester across organisations")
    void blocksDifferentOrg() {
      assertThatThrownBy(() -> service.validateBceidSameOrg(
          requester(UserType.BCEID, ORG_1),
          List.of(targetUser("X", GUID_A, UserType.BCEID).toBuilder()
              .businessGuid(ORG_2).build())))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("different organization");
    }

    @Test
    @DisplayName("fails closed when either organisation is unknown")
    void failsClosedOnMissingOrg() {
      assertThatThrownBy(() -> service.validateBceidSameOrg(
          requester(UserType.BCEID, ORG_1),
          List.of(targetUser("X", GUID_A, UserType.BCEID))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("business GUID is missing");

      assertThatThrownBy(() -> service.validateBceidSameOrg(
          requester(UserType.BCEID, null),
          List.of(targetUser("X", GUID_A, UserType.BCEID).toBuilder()
              .businessGuid(ORG_1).build())))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("splits a batch into allowed and rejected users")
    void splitsBatch() {
      var split = service.splitBySameOrg(
          requester(UserType.BCEID, ORG_1),
          List.of(
              targetUser("SAME", GUID_A, UserType.BCEID).toBuilder()
                  .businessGuid(ORG_1).build(),
              targetUser("OTHER", GUID_B, UserType.BCEID).toBuilder()
                  .businessGuid(ORG_2).build()),
          UserType.BCEID.getCode());

      assertThat(split.validUsers()).singleElement()
          .extracting(TargetUser::userName).isEqualTo("SAME");
      assertThat(split.failedUsers()).singleElement()
          .extracting(u -> u.userName()).isEqualTo("OTHER");
    }

    @Test
    @DisplayName("passes everything through when granting IDIR users")
    void idirGrantSkipsSplit() {
      List<TargetUser> users = List.of(targetUser("A", GUID_A, UserType.IDIR));

      var split = service.splitBySameOrg(
          requester(UserType.BCEID, ORG_1), users, UserType.IDIR.getCode());

      assertThat(split.validUsers()).isEqualTo(users);
      assertThat(split.failedUsers()).isEmpty();
    }
  }
}
