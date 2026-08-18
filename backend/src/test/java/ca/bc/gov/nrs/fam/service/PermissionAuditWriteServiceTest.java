package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.EmailSendingStatus;
import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsPermissionType;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsDto;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentResult;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeAudit;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeType;
import ca.bc.gov.nrs.fam.repository.FamPrivilegeChangeAuditRepository;
import ca.bc.gov.nrs.fam.security.Requester;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The audit trail is the one thing that stayed in FAM's own tables.
 *
 * <p>CSS keeps no history of who granted what to whom, so anything not written
 * here is not recorded anywhere.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PermissionAuditWriteService (CSS-sourced grants)")
class PermissionAuditWriteServiceTest {

  private static final String TARGET_GUID = "BBBBCCCCDDDDEEEEFFFF000011112222";

  @Mock private FamPrivilegeChangeAuditRepository auditRepository;
  @Mock private EntityManager entityManager;
  /**
   * Configured the way the application configures it
   * ({@code spring.jackson.property-naming-strategy: SNAKE_CASE}). A default
   * mapper would write camelCase here and pass assertions that production JSON
   * would fail.
   */
  @org.mockito.Spy private ObjectMapper objectMapper = new ObjectMapper()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

  @InjectMocks private PermissionAuditWriteService service;

  private final Requester requester = Requester.builder()
      .userId(1L).userName("JSMITH").userGuid("AAAA1111")
      .firstName("Jane").lastName("Smith").email("jane@gov.bc.ca")
      .build();

  private static CssUserRoleAssignmentResult assigned(String roleName) {
    return new CssUserRoleAssignmentResult(roleName, true, true, null,
        EmailSendingStatus.NOT_REQUIRED);
  }

  private static CssUserRoleAssignmentResult failed(String roleName) {
    return new CssUserRoleAssignmentResult(roleName, false, false, "boom",
        EmailSendingStatus.NOT_REQUIRED);
  }

  private FamPrivilegeChangeAudit captureSaved() {
    ArgumentCaptor<FamPrivilegeChangeAudit> captor =
        ArgumentCaptor.forClass(FamPrivilegeChangeAudit.class);
    verify(auditRepository).save(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("records the CSS integration and environment, not a FAM application id")
  void recordsCssIdentifiers() {
    when(entityManager.getReference(any(), any())).thenReturn(new FamPrivilegeChangeType());

    service.storeCssGranted(requester, TARGET_GUID, "I", 6538, "dev",
        "CHR_FREP_EDITOR", "DISTRICT", List.of(assigned("CHR_FREP_EDITOR_DISTRICT-DCC")));

    FamPrivilegeChangeAudit saved = captureSaved();
    assertThat(saved.getCssIntegrationId()).isEqualTo(6538);
    assertThat(saved.getCssEnvironment()).isEqualTo("dev");
    assertThat(saved.getTargetUserGuid()).isEqualTo(TARGET_GUID);
    assertThat(saved.getTargetUserTypeCode()).isEqualTo("I");
    assertThat(saved.getPerformerUserGuid()).isEqualTo("AAAA1111");
  }

  @Test
  @DisplayName("records only the roles that were actually assigned")
  void recordsOnlySuccesses() {
    // A partly successful grant must not claim the failures, or the trail
    // overstates what the user was given.
    when(entityManager.getReference(any(), any())).thenReturn(new FamPrivilegeChangeType());

    service.storeCssGranted(requester, TARGET_GUID, "I", 1, "dev",
        "R", "DISTRICT",
        List.of(assigned("R_DISTRICT-DCC"), failed("R_DISTRICT-DQU")));

    assertThat(captureSaved().getPrivilegeDetails())
        .contains("DCC")
        .doesNotContain("DQU");
  }

  @Test
  @DisplayName("writes nothing when no assignment succeeded")
  void writesNothingWhenAllFailed() {
    service.storeCssGranted(requester, TARGET_GUID, "I", 1, "dev",
        "R", "DISTRICT", List.of(failed("R_DISTRICT-DCC")));

    verify(auditRepository, never()).save(any());
  }

  @Test
  @DisplayName("recovers the scope value from the generated role name")
  void recoversScopeFromRoleName() {
    // The role name is the only place CSS records the scope.
    when(entityManager.getReference(any(), any())).thenReturn(new FamPrivilegeChangeType());

    service.storeCssGranted(requester, TARGET_GUID, "I", 1, "dev",
        "FOM_SUBMITTER", "FOREST_CLIENT",
        List.of(assigned("FOM_SUBMITTER_FOREST_CLIENT-00001018")));

    assertThat(captureSaved().getPrivilegeDetails()).contains("00001018");
  }

  @Test
  @DisplayName("an unscoped grant records the role with no scopes")
  void unscopedGrantHasNoScopes() {
    when(entityManager.getReference(any(), any())).thenReturn(new FamPrivilegeChangeType());

    service.storeCssGranted(requester, TARGET_GUID, "I", 1, "dev",
        "FREP_ADMINISTRATOR", null, List.of(assigned("FREP_ADMINISTRATOR")));

    String details = captureSaved().getPrivilegeDetails();
    assertThat(details).contains("FREP_ADMINISTRATOR");
    assertThat(details).contains("\"scopes\":null");
  }

  @Test
  @DisplayName("snapshots the performer rather than only their id")
  void snapshotsPerformer() {
    // So the trail stays readable after the user is renamed or removed.
    when(entityManager.getReference(any(), any())).thenReturn(new FamPrivilegeChangeType());

    service.storeCssGranted(requester, TARGET_GUID, "I", 1, "dev",
        "R", null, List.of(assigned("R")));

    assertThat(captureSaved().getChangePerformerUserDetails())
        .contains("JSMITH").contains("jane@gov.bc.ca");
  }

  @Test
  @DisplayName("records a revocation from the role names as they were")
  void recordsRevocation() {
    when(entityManager.getReference(any(), any())).thenReturn(new FamPrivilegeChangeType());

    service.storeCssRevoked(requester, TARGET_GUID, "I", 6538, "dev",
        "CHR_FREP_EDITOR", "DISTRICT", List.of("CHR_FREP_EDITOR_DISTRICT-DCC"));

    FamPrivilegeChangeAudit saved = captureSaved();
    assertThat(saved.getPrivilegeDetails()).contains("DCC");
    assertThat(saved.getCssIntegrationId()).isEqualTo(6538);
  }

  @Test
  @DisplayName("writes nothing when a revocation removed nothing")
  void revocationOfNothingWritesNothing() {
    service.storeCssRevoked(requester, TARGET_GUID, "I", 1, "dev", "R", null, List.of());

    verify(auditRepository, never()).save(any());
  }

  @Test
  @DisplayName("attributes a change with no requester to the system")
  void systemChangeIsAttributedToSystem() {
    when(entityManager.getReference(any(), any())).thenReturn(new FamPrivilegeChangeType());

    service.storeCssGranted(null, TARGET_GUID, "I", 1, "dev",
        "R", null, List.of(assigned("R")));

    FamPrivilegeChangeAudit saved = captureSaved();
    assertThat(saved.getCreateUser()).isEqualTo("system");
    assertThat(saved.getPerformerUserGuid()).isNull();
    assertThat(saved.getChangePerformerUserDetails()).contains("system");
  }

  // ---------------------------------------------------------- role definitions

  @Test
  @DisplayName("records who defined a role, and on which application")
  void recordsRoleDefinition() {
    // CSS keeps no history of role definitions, so this row is the only record.
    when(entityManager.getReference(any(), any())).thenReturn(new FamPrivilegeChangeType());

    service.storeRoleCreated(requester, 6538, "dev",
        "FREP_ADMINISTRATOR", "FREP Administrator", null, null);

    FamPrivilegeChangeAudit saved = captureSaved();
    assertThat(saved.getCssIntegrationId()).isEqualTo(6538);
    assertThat(saved.getCssEnvironment()).isEqualTo("dev");
    assertThat(saved.getPerformerUserGuid()).isEqualTo("AAAA1111");
    assertThat(saved.getPrivilegeDetails())
        .contains("FREP_ADMINISTRATOR")
        .contains("FREP Administrator");
  }

  @Test
  @DisplayName("leaves the target user empty, because there is not one")
  void roleDefinitionHasNoTargetUser() {
    // Pointing it at the performer would read as somebody granting themselves
    // something.
    when(entityManager.getReference(any(), any())).thenReturn(new FamPrivilegeChangeType());

    service.storeRoleCreated(requester, 1, "dev", "R_ONE", "Role one", null, null);

    FamPrivilegeChangeAudit saved = captureSaved();
    assertThat(saved.getTargetUserGuid()).isNull();
    assertThat(saved.getTargetUserTypeCode()).isNull();
  }

  @Test
  @DisplayName("records the scope a role will require")
  void recordsRequiredScope() {
    when(entityManager.getReference(any(), any())).thenReturn(new FamPrivilegeChangeType());

    service.storeRoleCreated(requester, 1, "dev", "R_ONE", "Role one", null, "DISTRICT");

    assertThat(captureSaved().getPrivilegeDetails()).contains("District");
  }

  @Test
  @DisplayName("an unscoped role records no required scope, rather than defaulting to one")
  void unscopedRoleHasNullRequiredScope() {
    // The grant path defaults an unrecognised scope to CLIENT because there a
    // role is always scoped by something. Here "none" is a real answer, and
    // recording it as CLIENT would misstate what was created.
    when(entityManager.getReference(any(), any())).thenReturn(new FamPrivilegeChangeType());

    service.storeRoleCreated(requester, 1, "dev", "R_ONE", "Role one", null, null);

    assertThat(captureSaved().getPrivilegeDetails())
        .contains("\"required_scope_type\":null")
        .doesNotContain("Client");
  }

  @Test
  @DisplayName("the definition document still reads as an ordinary privilege detail")
  void definitionDocumentIsBackwardsReadable() {
    // The history reader parses every row it returns as a PrivilegeDetailsDto.
    // This row cannot reach it today - history is keyed on a target user GUID and
    // this one has none - but the document outlives the code that wrote it, so
    // reading it the old way has to yield a coherent record rather than an error.
    when(entityManager.getReference(any(), any())).thenReturn(new FamPrivilegeChangeType());

    service.storeRoleCreated(requester, 1, "dev",
        "FREP_ADMINISTRATOR", "FREP Administrator", null, "DISTRICT");

    String json = captureSaved().getPrivilegeDetails();

    assertThatCode(() -> {
      // Built the way Spring Boot builds the application's mapper, rather than
      // with the tolerance switched on by hand - the point is that the real
      // reader copes, not that a specially configured one does.
      PrivilegeDetailsDto asOldShape =
          Jackson2ObjectMapperBuilder.json()
              .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
              .build()
              .readValue(json, PrivilegeDetailsDto.class);

      assertThat(asOldShape.permissionType())
          .isEqualTo(PrivilegeDetailsPermissionType.ROLE_DEFINITION);
      assertThat(asOldShape.roles()).singleElement()
          .satisfies(role -> assertThat(role.role()).isEqualTo("FREP_ADMINISTRATOR"));
      assertThat(asOldShape.isConsistent()).isTrue();
    }).doesNotThrowAnyException();
  }
}
