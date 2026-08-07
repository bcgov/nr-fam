package ca.bc.gov.nrs.fam.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.repository.FamAccessControlPrivilegeRepository;
import ca.bc.gov.nrs.fam.service.ApplicationService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorizationService (port of router_guards.py)")
class AuthorizationServiceTest {

  @Mock
  private ApplicationService applicationService;

  @Mock
  private FamAccessControlPrivilegeRepository accessControlPrivilegeRepository;

  @InjectMocks
  private AuthorizationService authorizationService;

  private static Requester requester(UserType type, boolean delegatedAdmin, String... roles) {
    return Requester.builder()
        .userId(1L)
        .userName("TESTER")
        .userType(type)
        .userGuid("0".repeat(32))
        .accessRoles(List.of(roles))
        .isDelegatedAdmin(delegatedAdmin)
        .build();
  }

  private static FamRole roleIn(String applicationName, String appEnvironment) {
    FamApplication application = new FamApplication();
    application.setApplicationId(10L);
    application.setApplicationName(applicationName);
    application.setAppEnvironment(appEnvironment);

    FamRole role = new FamRole();
    role.setRoleId(100L);
    role.setRoleName("SOME_ROLE");
    role.setApplication(application);
    return role;
  }

  @Nested
  @DisplayName("authorize")
  class Authorize {

    @Test
    @DisplayName("passes when the token carries any admin group")
    void allowsWithAdminGroup() {
      assertThatCode(() -> authorizationService.authorize(
          requester(UserType.IDIR, false, "FOM_DEV_ADMIN"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes for a delegated admin with no admin groups")
    void allowsDelegatedAdminWithoutGroups() {
      assertThatCode(() -> authorizationService.authorize(
          requester(UserType.BCEID, true))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a user who is neither, with authorization_groups_required")
    void rejectsWithoutAnyAdminCapacity() {
      assertThatThrownBy(() -> authorizationService.authorize(requester(UserType.IDIR, false)))
          .isInstanceOf(FamHttpException.class)
          .extracting("code", "status")
          .containsExactly(ErrorCode.GROUPS_REQUIRED, HttpStatus.FORBIDDEN);
    }
  }

  @Nested
  @DisplayName("authorizeByAppId")
  class AuthorizeByAppId {

    @Test
    @DisplayName("passes for an application admin without touching privileges")
    void allowsAppAdmin() {
      when(applicationService.isAppAdmin(anyLong(), any())).thenReturn(true);

      assertThatCode(() -> authorizationService.authorizeByAppId(
          10L, requester(UserType.IDIR, false, "FOM_DEV_ADMIN"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes for a delegated admin of that application")
    void allowsDelegatedAdminOfApplication() {
      when(applicationService.isAppAdmin(anyLong(), any())).thenReturn(false);
      when(accessControlPrivilegeRepository.findManagedRoleIds(1L, 10L))
          .thenReturn(List.of(100L));

      assertThatCode(() -> authorizationService.authorizeByAppId(
          10L, requester(UserType.BCEID, true))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a delegated admin of a different application")
    void rejectsDelegatedAdminOfOtherApplication() {
      when(applicationService.isAppAdmin(anyLong(), any())).thenReturn(false);
      when(accessControlPrivilegeRepository.findManagedRoleIds(1L, 10L)).thenReturn(List.of());

      assertThatThrownBy(() -> authorizationService.authorizeByAppId(
          10L, requester(UserType.BCEID, true)))
          .isInstanceOf(FamHttpException.class)
          .extracting("code", "status")
          .containsExactly(ErrorCode.PERMISSION_REQUIRED, HttpStatus.FORBIDDEN);
    }
  }

  @Nested
  @DisplayName("enforceBceidTermsConditions")
  class TermsConditions {

    @Test
    @DisplayName("rejects with HTTP 400 so the frontend can show the terms dialog")
    void rejectsWhenAcceptanceOutstanding() {
      Requester needsTerms = requester(UserType.BCEID, true).toBuilder()
          .requiresAcceptTc(true).build();

      assertThatThrownBy(() -> authorizationService.enforceBceidTermsConditions(needsTerms))
          .isInstanceOf(FamHttpException.class)
          .extracting("code", "status")
          .containsExactly(ErrorCode.TERMS_CONDITIONS_REQUIRED, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("passes once accepted")
    void allowsWhenAccepted() {
      assertThatCode(() -> authorizationService.enforceBceidTermsConditions(
          requester(UserType.BCEID, true))).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("internalOnlyAction")
  class InternalOnly {

    @Test
    @DisplayName("allows IDIR")
    void allowsIdir() {
      assertThatCode(() -> authorizationService.internalOnlyAction(
          requester(UserType.IDIR, false))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects BCeID")
    void rejectsBceid() {
      assertThatThrownBy(() -> authorizationService.internalOnlyAction(
          requester(UserType.BCEID, true)))
          .isInstanceOf(FamHttpException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.EXTERNAL_USER_ACTION_PROHIBITED);
    }

    @Test
    @DisplayName("rejects BCSC, which is neither IDIR nor BCeID")
    void rejectsBcsc() {
      Requester bcsc = requester(UserType.IDIR, false).toBuilder()
          .userType(UserType.BCSC_PROD).build();

      assertThatThrownBy(() -> authorizationService.internalOnlyAction(bcsc))
          .isInstanceOf(FamHttpException.class);
    }
  }

  @Nested
  @DisplayName("isSelfGrantExempt")
  class SelfGrantExemption {

    @ParameterizedTest(name = "{0} in {1} -> exempt={2}")
    @CsvSource({
        // App admins may self-manage on non-production instances of other apps.
        "FOM_DEV,   DEV,  true",
        "FOM_TEST,  TEST, true",
        // Production is never exempt.
        "FOM_PROD,  PROD, false",
        // FAM itself is never exempt, whatever its environment.
        "FAM,       DEV,  false",
    })
    @DisplayName("allows self-management only on DEV/TEST instances of other applications")
    void exemptionByApplicationAndEnvironment(
        String applicationName, String environment, boolean expected) {

      FamRole role = roleIn(applicationName, environment);
      Requester appAdmin = requester(UserType.IDIR, false, applicationName + "_ADMIN");

      assertThat(authorizationService.isSelfGrantExempt(role, appAdmin)).isEqualTo(expected);
    }

    @Test
    @DisplayName("fails closed when the application environment is null")
    void failsClosedOnMissingEnvironment() {
      FamRole role = roleIn("FOM_DEV", null);
      Requester appAdmin = requester(UserType.IDIR, false, "FOM_DEV_ADMIN");

      assertThat(authorizationService.isSelfGrantExempt(role, appAdmin)).isFalse();
    }

    @Test
    @DisplayName("fails closed when the application environment is unrecognised")
    void failsClosedOnUnknownEnvironment() {
      FamRole role = roleIn("FOM_DEV", "QA");
      Requester appAdmin = requester(UserType.IDIR, false, "FOM_DEV_ADMIN");

      assertThat(authorizationService.isSelfGrantExempt(role, appAdmin)).isFalse();
    }

    @Test
    @DisplayName("never exempts a delegated admin, only an application admin")
    void doesNotExemptDelegatedAdmin() {
      FamRole role = roleIn("FOM_DEV", "DEV");
      Requester delegatedAdmin = requester(UserType.BCEID, true);

      assertThat(authorizationService.isSelfGrantExempt(role, delegatedAdmin)).isFalse();
    }
  }

  @Nested
  @DisplayName("requirePrivilegeOnRole")
  class PrivilegeOnRole {

    @Test
    @DisplayName("rejects when the delegated admin does not manage the role")
    void rejectsWithoutPrivilege() {
      when(accessControlPrivilegeRepository.findByUserUserIdAndRoleRoleId(1L, 100L))
          .thenReturn(Optional.empty());

      assertThatThrownBy(() -> authorizationService.requirePrivilegeOnRole(
          requester(UserType.BCEID, true), 100L))
          .isInstanceOf(FamHttpException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.PERMISSION_REQUIRED);
    }
  }
}
