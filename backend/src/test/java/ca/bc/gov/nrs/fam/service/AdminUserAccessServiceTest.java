package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import ca.bc.gov.nrs.fam.constants.AppEnv;
import ca.bc.gov.nrs.fam.constants.RoleType;
import ca.bc.gov.nrs.fam.dto.AdminUserAccessResponse;
import ca.bc.gov.nrs.fam.dto.FamAuthGrantDto;
import ca.bc.gov.nrs.fam.dto.FamGrantDetailDto;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.entity.FamForestClient;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.repository.FamAccessControlPrivilegeRepository;
import ca.bc.gov.nrs.fam.repository.FamApplicationAdminRepository;
import ca.bc.gov.nrs.fam.repository.FamApplicationRepository;
import ca.bc.gov.nrs.fam.repository.FamRoleRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The access-grant tree the frontend's whole permission model is built from.
 *
 * <p>The shape is load-bearing: a capacity the user does not hold must be absent
 * from {@code access}, not present-and-empty, or the UI offers actions that will
 * be refused.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminUserAccessService (port of admin_user_access_service.py)")
class AdminUserAccessServiceTest {

  private static final Long USER_ID = 1L;

  @Mock private FamApplicationAdminRepository applicationAdminRepository;
  @Mock private FamAccessControlPrivilegeRepository accessControlPrivilegeRepository;
  @Mock private FamApplicationRepository applicationRepository;
  @Mock private FamRoleRepository roleRepository;

  @InjectMocks private AdminUserAccessService service;

  private static FamApplication application(Long id, String name, String env) {
    FamApplication application = new FamApplication();
    application.setApplicationId(id);
    application.setApplicationName(name);
    application.setApplicationDescription(name + " description");
    application.setAppEnvironment(env);
    return application;
  }

  private static FamRole role(Long id, String name, FamApplication application, FamRole parent) {
    FamRole role = new FamRole();
    role.setRoleId(id);
    role.setRoleName(name);
    role.setDisplayName(name);
    role.setRoleTypeCode(
        parent == null ? RoleType.ABSTRACT.getCode() : RoleType.CONCRETE.getCode());
    role.setApplication(application);
    role.setParentRole(parent);
    return role;
  }

  private static FamRole childRole(
      Long id, String name, FamApplication application, FamRole parent, String clientNumber) {
    FamRole role = role(id, name, application, parent);
    FamForestClient forestClient = new FamForestClient();
    forestClient.setForestClientNumber(clientNumber);
    role.setForestClient(forestClient);
    return role;
  }

  private FamAuthGrantDto grantFor(AdminUserAccessResponse response, AdminRoleAuthGroup key) {
    return response.access().stream()
        .filter(g -> g.authKey() == key)
        .findFirst()
        .orElse(null);
  }

  @Test
  @DisplayName("a user with no admin access anywhere gets an empty list, not an error")
  void noAccessYieldsEmptyList() {
    when(applicationAdminRepository.findAdministeredApplications(USER_ID))
        .thenReturn(List.of());
    when(accessControlPrivilegeRepository.findDelegatedAdminGrantedRoles(USER_ID))
        .thenReturn(List.of());

    assertThat(service.getAccessGrants(USER_ID).access()).isEmpty();
  }

  @Test
  @DisplayName("a FAM admin may administer every application")
  void famAdminGetsAllApplications() {
    when(applicationAdminRepository.findAdministeredApplications(USER_ID))
        .thenReturn(List.of(application(1L, "FAM", null)));
    when(accessControlPrivilegeRepository.findDelegatedAdminGrantedRoles(USER_ID))
        .thenReturn(List.of());
    when(applicationRepository.findAll()).thenReturn(List.of(
        application(1L, "FAM", null),
        application(2L, "FOM_DEV", "DEV"),
        application(3L, "SPAR_TEST", "TEST")));

    FamAuthGrantDto grant = grantFor(
        service.getAccessGrants(USER_ID), AdminRoleAuthGroup.FAM_ADMIN);

    assertThat(grant).isNotNull();
    assertThat(grant.grants()).hasSize(3);
    // FAM_ADMIN authority is over the application as a whole, so no roles.
    assertThat(grant.grants()).allSatisfy(detail -> assertThat(detail.roles()).isNull());
  }

  @Test
  @DisplayName("FAM itself is not listed as an application the user is APP_ADMIN of")
  void famIsExcludedFromAppAdminGrant() {
    // FAM's presence confers FAM_ADMIN; it is not one of the administered apps.
    when(applicationAdminRepository.findAdministeredApplications(USER_ID))
        .thenReturn(List.of(application(1L, "FAM", null), application(2L, "FOM_DEV", "DEV")));
    when(accessControlPrivilegeRepository.findDelegatedAdminGrantedRoles(USER_ID))
        .thenReturn(List.of());
    when(applicationRepository.findAll()).thenReturn(List.of());
    when(roleRepository.findBaseRolesByApplicationId(anyLong())).thenReturn(List.of());

    FamAuthGrantDto appAdmin = grantFor(
        service.getAccessGrants(USER_ID), AdminRoleAuthGroup.APP_ADMIN);

    assertThat(appAdmin.grants()).singleElement()
        .extracting(detail -> detail.application().name())
        .isEqualTo("FOM");
  }

  @Test
  @DisplayName("an app admin gets no APP_ADMIN capacity when they administer nothing but FAM")
  void famOnlyAdminHasNoAppAdminCapacity() {
    when(applicationAdminRepository.findAdministeredApplications(USER_ID))
        .thenReturn(List.of(application(1L, "FAM", null)));
    when(accessControlPrivilegeRepository.findDelegatedAdminGrantedRoles(USER_ID))
        .thenReturn(List.of());
    when(applicationRepository.findAll()).thenReturn(List.of());

    assertThat(grantFor(service.getAccessGrants(USER_ID), AdminRoleAuthGroup.APP_ADMIN))
        .isNull();
  }

  @Test
  @DisplayName("an app admin's roles exclude client-scoped child roles")
  void appAdminRolesExcludeChildRoles() {
    FamApplication fom = application(2L, "FOM_DEV", "DEV");
    when(applicationAdminRepository.findAdministeredApplications(USER_ID))
        .thenReturn(List.of(fom));
    when(accessControlPrivilegeRepository.findDelegatedAdminGrantedRoles(USER_ID))
        .thenReturn(List.of());
    // The repository filters child roles out; this asserts the base query is used.
    when(roleRepository.findBaseRolesByApplicationId(2L))
        .thenReturn(List.of(role(10L, "FOM_REVIEWER", fom, null)));

    FamAuthGrantDto grant = grantFor(
        service.getAccessGrants(USER_ID), AdminRoleAuthGroup.APP_ADMIN);

    assertThat(grant.grants()).singleElement()
        .extracting(FamGrantDetailDto::roles).asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .singleElement()
        .extracting("name").isEqualTo("FOM_REVIEWER");
    verify(roleRepository).findBaseRolesByApplicationId(2L);
    verify(roleRepository, never()).findByApplicationApplicationId(any());
  }

  @Test
  @DisplayName("a delegated admin's client-scoped roles collapse onto their parent")
  void delegatedAdminChildRolesCollapseOntoParent() {
    // FAM materialises one child role per client; the UI shows one role with a
    // list of clients.
    FamApplication fom = application(2L, "FOM_DEV", "DEV");
    FamRole parent = role(10L, "FOM_REVIEWER", fom, null);

    when(applicationAdminRepository.findAdministeredApplications(USER_ID))
        .thenReturn(List.of());
    when(accessControlPrivilegeRepository.findDelegatedAdminGrantedRoles(USER_ID))
        .thenReturn(List.of(
            childRole(11L, "FOM_REVIEWER_00001011", fom, parent, "00001011"),
            childRole(12L, "FOM_REVIEWER_00001012", fom, parent, "00001012")));

    FamAuthGrantDto grant = grantFor(
        service.getAccessGrants(USER_ID), AdminRoleAuthGroup.DELEGATED_ADMIN);

    assertThat(grant.grants()).singleElement().satisfies(detail -> {
      assertThat(detail.roles()).singleElement().satisfies(roleGrant -> {
        // The parent role, not either child.
        assertThat(roleGrant.id()).isEqualTo(10L);
        assertThat(roleGrant.name()).isEqualTo("FOM_REVIEWER");
        assertThat(roleGrant.forestClients()).extracting("forestClientNumber")
            .containsExactly("00001011", "00001012");
      });
    });
  }

  @Test
  @DisplayName("a delegated admin's unscoped roles are reported as themselves")
  void delegatedAdminUnscopedRolesReportedDirectly() {
    FamApplication fom = application(2L, "FOM_DEV", "DEV");

    when(applicationAdminRepository.findAdministeredApplications(USER_ID))
        .thenReturn(List.of());
    when(accessControlPrivilegeRepository.findDelegatedAdminGrantedRoles(USER_ID))
        .thenReturn(List.of(role(10L, "FOM_REVIEWER", fom, null)));

    FamAuthGrantDto grant = grantFor(
        service.getAccessGrants(USER_ID), AdminRoleAuthGroup.DELEGATED_ADMIN);

    assertThat(grant.grants()).singleElement()
        .extracting(FamGrantDetailDto::roles).asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .singleElement()
        .extracting("id", "forestClients")
        .containsExactly(10L, null);
  }

  @Test
  @DisplayName("delegated admin grants are grouped per application")
  void delegatedAdminGrantsGroupedByApplication() {
    FamApplication fom = application(2L, "FOM_DEV", "DEV");
    FamApplication spar = application(3L, "SPAR_TEST", "TEST");

    when(applicationAdminRepository.findAdministeredApplications(USER_ID))
        .thenReturn(List.of());
    when(accessControlPrivilegeRepository.findDelegatedAdminGrantedRoles(USER_ID))
        .thenReturn(List.of(
            role(10L, "FOM_REVIEWER", fom, null),
            role(20L, "SPAR_VIEWER", spar, null)));

    FamAuthGrantDto grant = grantFor(
        service.getAccessGrants(USER_ID), AdminRoleAuthGroup.DELEGATED_ADMIN);

    assertThat(grant.grants()).hasSize(2)
        .extracting(detail -> detail.application().name())
        .containsExactly("FOM", "SPAR");
  }

  @Test
  @DisplayName("all three capacities can be held at once")
  void allThreeCapacities() {
    FamApplication fom = application(2L, "FOM_DEV", "DEV");

    when(applicationAdminRepository.findAdministeredApplications(USER_ID))
        .thenReturn(List.of(application(1L, "FAM", null), fom));
    when(accessControlPrivilegeRepository.findDelegatedAdminGrantedRoles(USER_ID))
        .thenReturn(List.of(role(10L, "FOM_REVIEWER", fom, null)));
    when(applicationRepository.findAll()).thenReturn(List.of(application(1L, "FAM", null)));
    when(roleRepository.findBaseRolesByApplicationId(anyLong())).thenReturn(List.of());

    assertThat(service.getAccessGrants(USER_ID).access())
        .extracting(FamAuthGrantDto::authKey)
        .containsExactly(
            AdminRoleAuthGroup.FAM_ADMIN,
            AdminRoleAuthGroup.APP_ADMIN,
            AdminRoleAuthGroup.DELEGATED_ADMIN);
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "FOM_DEV,  FOM",
      "FOM_TEST, FOM",
      "FOM_PROD, FOM",
      "FAM,      FAM",
      "SPAR,     SPAR",
      // Only the three known suffixes are stripped.
      "FOM_QA,   FOM_QA",
  })
  @DisplayName("the environment suffix is stripped from the display name")
  void stripsAppEnvSuffix(String stored, String displayed) {
    // The environment is reported separately in `env`, so the suffix is noise.
    assertThat(AdminUserAccessService.removeAppEnvSuffix(stored)).isEqualTo(displayed);
  }

  @Test
  @DisplayName("the application environment is reported alongside the stripped name")
  void reportsApplicationEnvironment() {
    when(applicationAdminRepository.findAdministeredApplications(USER_ID))
        .thenReturn(List.of(application(2L, "FOM_DEV", "DEV")));
    when(accessControlPrivilegeRepository.findDelegatedAdminGrantedRoles(USER_ID))
        .thenReturn(List.of());
    when(roleRepository.findBaseRolesByApplicationId(anyLong())).thenReturn(List.of());

    FamAuthGrantDto grant = grantFor(
        service.getAccessGrants(USER_ID), AdminRoleAuthGroup.APP_ADMIN);

    assertThat(grant.grants()).singleElement()
        .extracting(detail -> detail.application().env())
        .isEqualTo(AppEnv.DEV);
  }
}
