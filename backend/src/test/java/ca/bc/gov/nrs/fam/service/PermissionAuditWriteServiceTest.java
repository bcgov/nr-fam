package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.FamApplicationDto;
import ca.bc.gov.nrs.fam.dto.FamApplicationUserRoleAssignmentGetDto;
import ca.bc.gov.nrs.fam.dto.FamForestClientDto;
import ca.bc.gov.nrs.fam.dto.FamRoleWithClientDto;
import ca.bc.gov.nrs.fam.dto.FamUserRoleAssignmentCreateResponse;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.entity.FamForestClient;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeAudit;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.entity.FamUserRoleXref;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.ForestClientIntegrationService;
import ca.bc.gov.nrs.fam.repository.FamPrivilegeChangeAuditRepository;
import ca.bc.gov.nrs.fam.security.Requester;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PermissionAuditWriteService (port of permission_audit_service.py)")
class PermissionAuditWriteServiceTest {

  @Mock private FamPrivilegeChangeAuditRepository auditRepository;
  @Mock private ForestClientIntegrationService forestClientIntegrationService;
  @Mock private ApiInstanceEnvResolver apiInstanceEnvResolver;
  @Mock private EntityManager entityManager;

  /** Mirrors the application-wide snake_case strategy, since the JSON is persisted. */
  private final ObjectMapper objectMapper = new ObjectMapper()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

  private PermissionAuditWriteService service;
  private FamApplication application;
  private FamUser targetUser;

  @BeforeEach
  void setUp() {
    service = new PermissionAuditWriteService(
        auditRepository, forestClientIntegrationService, apiInstanceEnvResolver,
        entityManager, objectMapper);

    application = new FamApplication();
    application.setApplicationId(1L);
    application.setApplicationName("FOM_DEV");
    application.setAppEnvironment("DEV");

    targetUser = new FamUser();
    targetUser.setUserId(100L);
    targetUser.setUserName("JSMITH");

    when(apiInstanceEnvResolver.resolve(any())).thenReturn(ApiInstanceEnv.TEST);
  }

  private static Requester requester() {
    return Requester.builder()
        .userId(1L).userName("ADMIN").firstName("Ada").lastName("Min")
        .email("ada@gov.bc.ca").userType(UserType.IDIR).build();
  }

  private static FamUserRoleAssignmentCreateResponse granted(String forestClientNumber) {
    FamForestClientDto forestClient = forestClientNumber == null
        ? null
        : new FamForestClientDto("ACME LTD.", forestClientNumber, null);

    return FamUserRoleAssignmentCreateResponse.success(
        new FamApplicationUserRoleAssignmentGetDto(1L, 100L, 10L, null,
            new FamRoleWithClientDto(10L, "FOM_REVIEWER", "C", "Reviewer", null,
                new FamApplicationDto(1L, "FOM_DEV", "Forest Operations Map"),
                forestClient, null),
            null, null));
  }

  private FamUserRoleXref revokedRecord(String forestClientNumber) {
    FamRole role = new FamRole();
    role.setRoleId(10L);
    role.setRoleName("FOM_REVIEWER");
    role.setDisplayName("Reviewer");
    role.setApplication(application);

    if (forestClientNumber != null) {
      FamForestClient forestClient = new FamForestClient();
      forestClient.setForestClientNumber(forestClientNumber);
      role.setForestClient(forestClient);
    }

    FamUserRoleXref xref = new FamUserRoleXref();
    xref.setUserRoleXrefId(500L);
    xref.setUser(targetUser);
    xref.setRole(role);
    return xref;
  }

  private FamPrivilegeChangeAudit captureSaved() {
    ArgumentCaptor<FamPrivilegeChangeAudit> captor =
        ArgumentCaptor.forClass(FamPrivilegeChangeAudit.class);
    verify(auditRepository).save(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("writes nothing when no grant in the batch succeeded")
  void writesNothingWithoutSuccesses() {
    // The caller passes every outcome, including conflicts and rejections; the
    // early return lives here rather than at the call site.
    service.storeGranted(requester(), targetUser,
        List.of(FamUserRoleAssignmentCreateResponse.failure(409, "already assigned")));

    verify(auditRepository, never()).save(any());
  }

  @Test
  @DisplayName("snapshots the performer so later edits cannot rewrite history")
  void snapshotsPerformer() {
    service.storeGranted(requester(), targetUser, List.of(granted(null)));

    assertThat(captureSaved().getChangePerformerUserDetails())
        .contains("\"username\":\"ADMIN\"")
        .contains("\"first_name\":\"Ada\"")
        .contains("\"email\":\"ada@gov.bc.ca\"");
  }

  @Test
  @DisplayName("puts the expiry on the role when the grant has no client scope")
  void unscopedGrantRecordsRoleLevelExpiry() {
    service.storeGranted(requester(), targetUser, List.of(granted(null)));

    String details = captureSaved().getPrivilegeDetails();
    assertThat(details)
        .contains("\"permission_type\":\"End User\"")
        .contains("\"role\":\"Reviewer\"")
        // No scopes key content for an unscoped grant.
        .contains("\"scopes\":null");
  }

  @Test
  @DisplayName("records one scope per forest client when the grant is scoped")
  void scopedGrantRecordsScopes() {
    service.storeGranted(requester(), targetUser,
        List.of(granted("00001011"), granted("00001012")));

    String details = captureSaved().getPrivilegeDetails();
    assertThat(details)
        .contains("\"scope_type\":\"Client\"")
        .contains("\"client_id\":\"00001011\"")
        .contains("\"client_id\":\"00001012\"")
        .contains("\"client_name\":\"ACME LTD.\"");
  }

  @Test
  @DisplayName("resolves the client name for a scoped revocation")
  void revokeResolvesClientName() {
    when(forestClientIntegrationService.search(anyList(), any(), anyBoolean()))
        .thenReturn(List.of(Map.of(
            "clientNumber", "00001011", "clientName", "ACME LTD.", "clientStatusCode", "ACT")));

    service.storeRevoked(requester(), revokedRecord("00001011"));

    assertThat(captureSaved().getPrivilegeDetails())
        .contains("\"client_id\":\"00001011\"")
        .contains("\"client_name\":\"ACME LTD.\"");
  }

  @Test
  @DisplayName("refuses to revoke when the scoped client cannot be named")
  void revokeFailsWhenClientUnknown() {
    // Unlike the read path, this does not soft-fail: an audit record that cannot
    // name the client it describes is not worth writing.
    when(forestClientIntegrationService.search(anyList(), any(), anyBoolean()))
        .thenReturn(List.of());

    assertThatThrownBy(() -> service.storeRevoked(requester(), revokedRecord("99999999")))
        .isInstanceOf(FamHttpException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.UNKNOWN_STATE);

    verify(auditRepository, never()).save(any());
  }

  @Test
  @DisplayName("does not call the Forest Client API for an unscoped revocation")
  void unscopedRevokeSkipsForestClientLookup() {
    service.storeRevoked(requester(), revokedRecord(null));

    verify(forestClientIntegrationService, never()).search(anyList(), any(), anyBoolean());
    assertThat(captureSaved().getPrivilegeDetails()).contains("\"role\":\"Reviewer\"");
  }

  @Test
  @DisplayName("sets change_date explicitly rather than leaving it to be derived")
  void setsChangeDate() {
    // change_date and create_date differ for backfilled rows, so it is never
    // inferred from the creation timestamp.
    service.storeGranted(requester(), targetUser, List.of(granted(null)));

    assertThat(captureSaved().getChangeDate()).isNotNull();
  }
}
