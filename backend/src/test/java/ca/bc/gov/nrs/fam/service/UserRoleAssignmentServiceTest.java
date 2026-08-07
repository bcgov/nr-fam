package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.RoleType;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.FamUserRoleAssignmentCreateRequest;
import ca.bc.gov.nrs.fam.dto.FamUserRoleAssignmentCreateResponse;
import ca.bc.gov.nrs.fam.dto.FamUserRoleAssignmentUserDto;
import ca.bc.gov.nrs.fam.dto.TargetUser;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.entity.FamForestClient;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.entity.FamUserRoleXref;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.ForestClientIntegrationService;
import ca.bc.gov.nrs.fam.repository.FamUserRoleXrefRepository;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The grant pipeline's partial-success behaviour.
 *
 * <p>A batch grant can name fifty users across several forest clients. The
 * defining property is that one failure never discards the rest, so most of these
 * tests assert on the mix of outcomes rather than on a single result.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserRoleAssignmentService (port of crud_user_role.py)")
class UserRoleAssignmentServiceTest {

  private static final String GUID_A = "A".repeat(32);

  @Mock private FamUserRoleXrefRepository userRoleXrefRepository;
  @Mock private UserService userService;
  @Mock private RoleService roleService;
  @Mock private ForestClientIntegrationService forestClientIntegrationService;
  @Mock private ApiInstanceEnvResolver apiInstanceEnvResolver;
  @Mock private TargetUserValidationService targetUserValidationService;
  @Mock private PermissionAuditWriteService permissionAuditWriteService;

  private final ExpiryDateParser expiryDateParser = new ExpiryDateParser();
  private final FamDtoMapper mapper = new FamDtoMapper();

  private UserRoleAssignmentService service;

  private FamApplication application;
  private FamUser famUser;

  @BeforeEach
  void setUp() {
    service = new UserRoleAssignmentService(
        userRoleXrefRepository, userService, roleService, forestClientIntegrationService,
        apiInstanceEnvResolver, targetUserValidationService, permissionAuditWriteService,
        expiryDateParser, mapper);

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
    when(userRoleXrefRepository.findByUserUserIdAndRoleRoleId(anyLong(), anyLong()))
        .thenReturn(Optional.empty());
    when(userRoleXrefRepository.save(any())).thenAnswer(invocation -> {
      FamUserRoleXref xref = invocation.getArgument(0);
      xref.setUserRoleXrefId(999L);
      return xref;
    });
    when(targetUserValidationService.splitBySameOrg(any(), anyList(), anyString()))
        .thenAnswer(invocation -> new TargetUserValidationService.SameOrgSplit(
            invocation.getArgument(1), List.of()));
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
    forestClient.setClientNumberId(5L);
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
        .userName("JSMITH").userGuid(GUID_A).userTypeCode(UserType.IDIR.getCode()).build();
  }

  private static FamUserRoleAssignmentCreateRequest request(
      Long roleId, List<String> forestClientNumbers) {
    return new FamUserRoleAssignmentCreateRequest(
        List.of(new FamUserRoleAssignmentUserDto("JSMITH", GUID_A)),
        UserType.IDIR, roleId, forestClientNumbers, false, null);
  }

  private static Map<String, Object> activeClient(String number) {
    return Map.of("clientNumber", number, "clientName", "ACME LTD.", "clientStatusCode", "ACT");
  }

  @Test
  @DisplayName("grants a concrete role")
  void grantsConcreteRole() {
    when(roleService.getRole(10L)).thenReturn(role(RoleType.CONCRETE.getCode()));

    List<FamUserRoleAssignmentCreateResponse> results = service.createUserRoleAssignmentMany(
        request(10L, null), List.of(targetUser()), requester());

    assertThat(results).singleElement()
        .extracting(FamUserRoleAssignmentCreateResponse::statusCode).isEqualTo(200);
    verify(permissionAuditWriteService).storeGranted(any(), eq(famUser), anyList());
  }

  @Test
  @DisplayName("reports an already-assigned role as 409 with the existing assignment")
  void reportsConflictForExistingAssignment() {
    FamRole concrete = role(RoleType.CONCRETE.getCode());
    when(roleService.getRole(10L)).thenReturn(concrete);

    FamUserRoleXref existing = new FamUserRoleXref();
    existing.setUserRoleXrefId(500L);
    existing.setUser(famUser);
    existing.setRole(concrete);
    when(userRoleXrefRepository.findByUserUserIdAndRoleRoleId(100L, 10L))
        .thenReturn(Optional.of(existing));

    List<FamUserRoleAssignmentCreateResponse> results = service.createUserRoleAssignmentMany(
        request(10L, null), List.of(targetUser()), requester());

    assertThat(results).singleElement().satisfies(result -> {
      assertThat(result.statusCode()).isEqualTo(409);
      // The existing assignment comes back so the UI can show what they already have.
      assertThat(result.detail()).isNotNull();
      assertThat(result.errorMessage()).contains("already assigned");
    });

    // The audit service is still called, but only ever with the conflict - it
    // filters to successful grants itself and writes nothing when there are none,
    // which is where upstream put that early return too. See
    // PermissionAuditWriteServiceTest for the write-side guarantee.
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<FamUserRoleAssignmentCreateResponse>> audited =
        ArgumentCaptor.forClass(List.class);
    verify(permissionAuditWriteService).storeGranted(any(), eq(famUser), audited.capture());
    assertThat(audited.getValue()).noneMatch(FamUserRoleAssignmentCreateResponse::isSuccess);
  }

  @Test
  @DisplayName("rejects an abstract role granted with no forest client numbers")
  void rejectsAbstractRoleWithoutClients() {
    when(roleService.getRole(10L)).thenReturn(role(RoleType.ABSTRACT.getCode()));

    List<FamUserRoleAssignmentCreateResponse> results = service.createUserRoleAssignmentMany(
        request(10L, List.of()), List.of(targetUser()), requester());

    assertThat(results).singleElement().satisfies(result -> {
      assertThat(result.statusCode()).isEqualTo(400);
      assertThat(result.errorMessage()).contains("missing forest client number");
    });
  }

  @Test
  @DisplayName("creates one child-role assignment per forest client")
  void grantsScopedRolePerClient() {
    FamRole parent = role(RoleType.ABSTRACT.getCode());
    when(roleService.getRole(10L)).thenReturn(parent);
    when(forestClientIntegrationService.search(eq(List.of("00001011")), any(), anyBoolean()))
        .thenReturn(List.of(activeClient("00001011")));
    when(forestClientIntegrationService.search(eq(List.of("00001012")), any(), anyBoolean()))
        .thenReturn(List.of(activeClient("00001012")));
    when(roleService.findOrCreateForestClientChildRole(anyString(), any(), any()))
        .thenAnswer(invocation -> childRole(invocation.getArgument(0)));

    List<FamUserRoleAssignmentCreateResponse> results = service.createUserRoleAssignmentMany(
        request(10L, List.of("00001011", "00001012")), List.of(targetUser()), requester());

    assertThat(results).hasSize(2)
        .allMatch(FamUserRoleAssignmentCreateResponse::isSuccess);
    // The client name is attached from the search so the UI and audit can show it.
    assertThat(results.get(0).detail().role().forestClient().clientName()).isEqualTo("ACME LTD.");
  }

  @Test
  @DisplayName("rejects a client number the Forest Client API does not know")
  void rejectsUnknownForestClient() {
    when(roleService.getRole(10L)).thenReturn(role(RoleType.ABSTRACT.getCode()));
    when(forestClientIntegrationService.search(anyList(), any(), anyBoolean()))
        .thenReturn(List.of());

    List<FamUserRoleAssignmentCreateResponse> results = service.createUserRoleAssignmentMany(
        request(10L, List.of("99999999")), List.of(targetUser()), requester());

    assertThat(results).singleElement().satisfies(result -> {
      assertThat(result.statusCode()).isEqualTo(400);
      assertThat(result.errorMessage()).contains("does not exist");
    });
  }

  @Test
  @DisplayName("rejects an inactive client and names its status")
  void rejectsInactiveForestClient() {
    when(roleService.getRole(10L)).thenReturn(role(RoleType.ABSTRACT.getCode()));
    when(forestClientIntegrationService.search(anyList(), any(), anyBoolean()))
        .thenReturn(List.of(Map.of(
            "clientNumber", "00001011", "clientName", "GONE LTD.", "clientStatusCode", "DAC")));

    List<FamUserRoleAssignmentCreateResponse> results = service.createUserRoleAssignmentMany(
        request(10L, List.of("00001011")), List.of(targetUser()), requester());

    assertThat(results).singleElement().satisfies(result -> {
      assertThat(result.statusCode()).isEqualTo(400);
      assertThat(result.errorMessage()).contains("not in active status").contains("DAC");
    });
  }

  @Test
  @DisplayName("one bad client does not stop the good ones")
  void partialSuccessAcrossClients() {
    when(roleService.getRole(10L)).thenReturn(role(RoleType.ABSTRACT.getCode()));
    when(forestClientIntegrationService.search(eq(List.of("00001011")), any(), anyBoolean()))
        .thenReturn(List.of(activeClient("00001011")));
    when(forestClientIntegrationService.search(eq(List.of("99999999")), any(), anyBoolean()))
        .thenReturn(List.of());
    when(roleService.findOrCreateForestClientChildRole(anyString(), any(), any()))
        .thenAnswer(invocation -> childRole(invocation.getArgument(0)));

    List<FamUserRoleAssignmentCreateResponse> results = service.createUserRoleAssignmentMany(
        request(10L, List.of("00001011", "99999999")), List.of(targetUser()), requester());

    assertThat(results).hasSize(2);
    assertThat(results).filteredOn(FamUserRoleAssignmentCreateResponse::isSuccess).hasSize(1);
    assertThat(results).filteredOn(r -> r.statusCode() == 400).hasSize(1);
  }

  @Test
  @DisplayName("reports users rejected by the organisation rule as 403, and grants the rest")
  void reportsSameOrgFailuresAsForbidden() {
    when(roleService.getRole(10L)).thenReturn(role(RoleType.CONCRETE.getCode()));
    when(targetUserValidationService.splitBySameOrg(any(), anyList(), anyString()))
        .thenReturn(new TargetUserValidationService.SameOrgSplit(
            List.of(targetUser()),
            List.of(new ca.bc.gov.nrs.fam.dto.FailedTargetUser(
                "OUTSIDER", GUID_A, "Managing user OUTSIDER from a different organization "
                + "is not allowed."))));

    List<FamUserRoleAssignmentCreateResponse> results = service.createUserRoleAssignmentMany(
        request(10L, null), List.of(targetUser()), requester());

    assertThat(results).hasSize(2);
    assertThat(results).filteredOn(r -> r.statusCode() == 403).singleElement()
        .extracting(FamUserRoleAssignmentCreateResponse::errorMessage)
        .asString().contains("different organization");
    assertThat(results).filteredOn(FamUserRoleAssignmentCreateResponse::isSuccess).hasSize(1);
  }

  @Test
  @DisplayName("rejects a role id that does not exist")
  void rejectsUnknownRole() {
    when(roleService.getRole(99L)).thenReturn(null);

    assertThatThrownBy(() -> service.createUserRoleAssignmentMany(
        request(99L, null), List.of(targetUser()), requester()))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("does not exist");
  }

  @Test
  @DisplayName("rejects an unsupported identity type outright")
  void rejectsUnsupportedUserType() {
    FamUserRoleAssignmentCreateRequest bad = new FamUserRoleAssignmentCreateRequest(
        List.of(new FamUserRoleAssignmentUserDto("JSMITH", GUID_A)),
        UserType.BCSC_PROD, 10L, null, false, null);

    assertThatThrownBy(() -> service.createUserRoleAssignmentMany(
        bad, List.of(targetUser()), requester()))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("Invalid user type");
  }

  @Test
  @DisplayName("audits the revocation before deleting the row")
  void auditsBeforeDeleting() {
    FamUserRoleXref xref = new FamUserRoleXref();
    xref.setUserRoleXrefId(500L);
    xref.setUser(famUser);
    xref.setRole(role(RoleType.CONCRETE.getCode()));
    when(userRoleXrefRepository.findById(500L)).thenReturn(Optional.of(xref));

    service.deleteUserRoleAssignment(requester(), 500L);

    var inOrder = org.mockito.Mockito.inOrder(permissionAuditWriteService, userRoleXrefRepository);
    inOrder.verify(permissionAuditWriteService).storeRevoked(any(), eq(xref));
    inOrder.verify(userRoleXrefRepository).delete(xref);
  }

  @Test
  @DisplayName("rejects revoking an assignment that does not exist")
  void rejectsUnknownAssignmentOnDelete() {
    when(userRoleXrefRepository.findById(404L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deleteUserRoleAssignment(requester(), 404L))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("user_role_xref_id");
  }
}
