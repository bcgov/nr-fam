package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.RoleType;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.FamAccessControlPrivilegeCreateRequest;
import ca.bc.gov.nrs.fam.dto.FamAccessControlPrivilegeCreateResponse;
import ca.bc.gov.nrs.fam.dto.TargetUser;
import ca.bc.gov.nrs.fam.entity.FamAccessControlPrivilege;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.entity.FamForestClient;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.ForestClientIntegrationService;
import ca.bc.gov.nrs.fam.repository.FamAccessControlPrivilegeRepository;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Delegated-admin grants.
 *
 * <p>The behaviour that most distinguishes this from the end-user grant path is
 * that an invalid forest client <strong>aborts the request</strong> instead of
 * failing one entry, so several tests pin that.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccessControlPrivilegeService (port of access_control_privilege_service.py)")
class AccessControlPrivilegeServiceTest {

  private static final String GUID = "A".repeat(32);

  @Mock private FamAccessControlPrivilegeRepository accessControlPrivilegeRepository;
  @Mock private UserService userService;
  @Mock private RoleService roleService;
  @Mock private ForestClientIntegrationService forestClientIntegrationService;
  @Mock private ApiInstanceEnvResolver apiInstanceEnvResolver;
  @Mock private AdminPermissionAuditWriteService permissionAuditWriteService;
  @Mock private AccessGrantedEmailService accessGrantedEmailService;

  private final FamDtoMapper mapper = new FamDtoMapper();

  private AccessControlPrivilegeService service;
  private FamApplication application;
  private FamUser famUser;

  @BeforeEach
  void setUp() {
    service = new AccessControlPrivilegeService(
        accessControlPrivilegeRepository, userService, roleService,
        forestClientIntegrationService, apiInstanceEnvResolver, permissionAuditWriteService,
        accessGrantedEmailService, mapper);

    application = new FamApplication();
    application.setApplicationId(1L);
    application.setApplicationName("FOM_DEV");
    application.setApplicationDescription("Forest Operations Map");
    application.setAppEnvironment("DEV");

    famUser = new FamUser();
    famUser.setUserId(100L);
    famUser.setUserName("JSMITH");
    famUser.setUserTypeCode(UserType.IDIR.getCode());

    when(userService.findOrCreate(any(), any(), any(), any())).thenReturn(famUser);
    when(userService.updateFromVerifiedTargetUser(anyLong(), any(), any())).thenReturn(famUser);
    when(apiInstanceEnvResolver.resolve(any())).thenReturn(ApiInstanceEnv.TEST);
    when(accessControlPrivilegeRepository.findByUserUserIdAndRoleRoleId(anyLong(), anyLong()))
        .thenReturn(Optional.empty());
    when(accessControlPrivilegeRepository.save(any())).thenAnswer(invocation -> {
      FamAccessControlPrivilege privilege = invocation.getArgument(0);
      privilege.setAccessControlPrivilegeId(999L);
      return privilege;
    });
  }

  private FamRole role(String roleTypeCode) {
    FamRole role = new FamRole();
    role.setRoleId(10L);
    role.setRoleName("FOM_REVIEWER");
    role.setDisplayName("Reviewer");
    role.setRoleTypeCode(roleTypeCode);
    role.setApplication(application);
    return role;
  }

  private FamRole childRole(String forestClientNumber) {
    FamRole child = role(RoleType.CONCRETE.getCode());
    child.setRoleId(11L);
    child.setRoleName("FOM_REVIEWER_" + forestClientNumber);
    FamForestClient forestClient = new FamForestClient();
    forestClient.setForestClientNumber(forestClientNumber);
    child.setForestClient(forestClient);
    return child;
  }

  private static Requester requester() {
    return Requester.builder()
        .userId(1L).userName("ADMIN").userType(UserType.IDIR)
        .userGuid("B".repeat(32)).oidcUserId("admin-sub").build();
  }

  private static TargetUser targetUser() {
    return TargetUser.builder()
        .userName("JSMITH").userGuid(GUID).userTypeCode(UserType.IDIR.getCode()).build();
  }

  private static FamAccessControlPrivilegeCreateRequest request(List<String> clients) {
    return new FamAccessControlPrivilegeCreateRequest(
        "JSMITH", GUID, UserType.IDIR, 10L, clients, false);
  }

  private static Map<String, Object> activeClient(String number) {
    return Map.of("clientNumber", number, "clientName", "ACME LTD.", "clientStatusCode", "ACT");
  }

  @Test
  @DisplayName("grants a concrete role directly")
  void grantsConcreteRole() {
    when(roleService.getRole(10L)).thenReturn(role(RoleType.CONCRETE.getCode()));

    List<FamAccessControlPrivilegeCreateResponse> results =
        service.createMany(request(null), requester(), targetUser());

    assertThat(results).singleElement()
        .extracting(FamAccessControlPrivilegeCreateResponse::statusCode).isEqualTo(200);
    verify(permissionAuditWriteService).storeDelegatedAdminGranted(any(), eq(famUser), anyList());
  }

  @Test
  @DisplayName("creates one privilege per forest client for an abstract role")
  void grantsScopedRolePerClient() {
    when(roleService.getRole(10L)).thenReturn(role(RoleType.ABSTRACT.getCode()));
    when(forestClientIntegrationService.search(eq(List.of("00001011")), any(), anyBoolean()))
        .thenReturn(List.of(activeClient("00001011")));
    when(forestClientIntegrationService.search(eq(List.of("00001012")), any(), anyBoolean()))
        .thenReturn(List.of(activeClient("00001012")));
    when(roleService.findOrCreateForestClientChildRole(anyString(), any(), any()))
        .thenAnswer(invocation -> childRole(invocation.getArgument(0)));

    List<FamAccessControlPrivilegeCreateResponse> results =
        service.createMany(request(List.of("00001011", "00001012")), requester(), targetUser());

    assertThat(results).hasSize(2)
        .allMatch(FamAccessControlPrivilegeCreateResponse::isSuccess);
    assertThat(results.get(0).detail().role().forestClient().clientName()).isEqualTo("ACME LTD.");
  }

  @Test
  @DisplayName("rejects an abstract role granted with no forest client numbers")
  void rejectsAbstractRoleWithoutClients() {
    when(roleService.getRole(10L)).thenReturn(role(RoleType.ABSTRACT.getCode()));

    assertThatThrownBy(() -> service.createMany(request(List.of()), requester(), targetUser()))
        .isInstanceOf(FamHttpException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.MISSING_KEY_ATTRIBUTE);
  }

  @Test
  @DisplayName("aborts the whole request when a forest client does not exist")
  void abortsOnUnknownForestClient() {
    // Unlike the end-user grant, which would fail just that one entry. Granting
    // administrative authority over some clients but not others, silently, would
    // be worse than refusing.
    when(roleService.getRole(10L)).thenReturn(role(RoleType.ABSTRACT.getCode()));
    when(forestClientIntegrationService.search(eq(List.of("00001011")), any(), anyBoolean()))
        .thenReturn(List.of(activeClient("00001011")));
    when(forestClientIntegrationService.search(eq(List.of("99999999")), any(), anyBoolean()))
        .thenReturn(List.of());
    when(roleService.findOrCreateForestClientChildRole(anyString(), any(), any()))
        .thenAnswer(invocation -> childRole(invocation.getArgument(0)));

    assertThatThrownBy(() -> service.createMany(
        request(List.of("00001011", "99999999")), requester(), targetUser()))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("does not exist");

    // Nothing is audited, because the request as a whole failed.
    verify(permissionAuditWriteService, never())
        .storeDelegatedAdminGranted(any(), any(), anyList());
  }

  @Test
  @DisplayName("aborts the whole request when a forest client is inactive")
  void abortsOnInactiveForestClient() {
    when(roleService.getRole(10L)).thenReturn(role(RoleType.ABSTRACT.getCode()));
    when(forestClientIntegrationService.search(anyList(), any(), anyBoolean()))
        .thenReturn(List.of(Map.of(
            "clientNumber", "00001011", "clientName", "GONE LTD.", "clientStatusCode", "DAC")));

    assertThatThrownBy(() -> service.createMany(
        request(List.of("00001011")), requester(), targetUser()))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("not in active status");
  }

  @Test
  @DisplayName("reports an existing privilege as 409 rather than duplicating it")
  void reportsConflictForExistingPrivilege() {
    FamRole concrete = role(RoleType.CONCRETE.getCode());
    when(roleService.getRole(10L)).thenReturn(concrete);

    FamAccessControlPrivilege existing = new FamAccessControlPrivilege();
    existing.setAccessControlPrivilegeId(500L);
    existing.setUser(famUser);
    existing.setRole(concrete);
    when(accessControlPrivilegeRepository.findByUserUserIdAndRoleRoleId(100L, 10L))
        .thenReturn(Optional.of(existing));

    List<FamAccessControlPrivilegeCreateResponse> results =
        service.createMany(request(null), requester(), targetUser());

    assertThat(results).singleElement().satisfies(result -> {
      assertThat(result.statusCode()).isEqualTo(409);
      assertThat(result.detail()).isNotNull();
      assertThat(result.errorMessage()).contains("already has the requested access");
    });
    verify(accessControlPrivilegeRepository, never()).save(any());
  }

  @Test
  @DisplayName("rejects a role id that does not exist")
  void rejectsUnknownRole() {
    when(roleService.getRole(10L)).thenReturn(null);

    assertThatThrownBy(() -> service.createMany(request(null), requester(), targetUser()))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("does not exist");
  }

  @Test
  @DisplayName("audits the revocation before deleting the row")
  void auditsBeforeDeleting() {
    FamAccessControlPrivilege privilege = new FamAccessControlPrivilege();
    privilege.setAccessControlPrivilegeId(500L);
    privilege.setUser(famUser);
    privilege.setRole(role(RoleType.CONCRETE.getCode()));
    when(accessControlPrivilegeRepository.findById(500L)).thenReturn(Optional.of(privilege));

    service.delete(requester(), 500L);

    var inOrder = org.mockito.Mockito.inOrder(
        permissionAuditWriteService, accessControlPrivilegeRepository);
    inOrder.verify(permissionAuditWriteService).storeDelegatedAdminRevoked(any(), eq(privilege));
    inOrder.verify(accessControlPrivilegeRepository).delete(privilege);
  }
}
