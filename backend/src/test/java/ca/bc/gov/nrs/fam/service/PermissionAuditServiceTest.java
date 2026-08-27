package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.PrivilegeChangeType;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.CssRoleDto;
import ca.bc.gov.nrs.fam.dto.PermissionAuditHistoryDto;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeAudit;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeType;
import ca.bc.gov.nrs.fam.integration.CssApiService;
import ca.bc.gov.nrs.fam.repository.FamPrivilegeChangeAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Reading the audit trail back.
 *
 * <p>What is worth pinning here is the display name. The trail records a role's
 * code, because that is the part that must stay true; what a person recognises
 * is its name, and that lives in a sidecar in CSS. The join between the two
 * happens here and nowhere else.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PermissionAuditService")
class PermissionAuditServiceTest {

  private static final int INTEGRATION = 6538;
  private static final String ENV = "dev";

  @Mock private FamPrivilegeChangeAuditRepository auditRepository;
  @Mock private CssApiService cssApiService;

  private PermissionAuditService service;

  @BeforeEach
  void setUp() {
    // The application's own mapper: the JSONB was written snake_case, so
    // reading it with a default mapper would silently produce empty details.
    ObjectMapper mapper = new ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    service = new PermissionAuditService(auditRepository, mapper, cssApiService);
  }

  private void trailHas(String privilegeDetailsJson) {
    FamPrivilegeChangeType type = new FamPrivilegeChangeType();
    type.setPrivilegeChangeTypeCode(PrivilegeChangeType.GRANT.name());
    type.setDescription("Role added");

    FamPrivilegeChangeAudit audit = new FamPrivilegeChangeAudit();
    audit.setPrivilegeChangeAuditId(UUID.randomUUID());
    audit.setChangeDate(LocalDateTime.now());
    audit.setPrivilegeChangeType(type);
    audit.setPrivilegeDetails(privilegeDetailsJson);

    when(auditRepository.findHistory(anyString(), anyInt(), anyString()))
        .thenReturn(List.of(audit));
  }

  private void cssHas(String... roleNames) {
    when(cssApiService.getRoles(INTEGRATION, ENV))
        .thenReturn(java.util.Arrays.stream(roleNames)
            .map(name -> new CssRoleDto(name, false))
            .toList());
  }

  private List<PermissionAuditHistoryDto> history() {
    return service.getHistory("ABC123", UserType.IDIR, INTEGRATION, ENV);
  }

  private String roleNameOf(PermissionAuditHistoryDto row) {
    return row.privilegeDetails().roles().get(0).roleDisplayName();
  }

  @Test
  void namesTheRoleFromItsLabelSidecar() {
    trailHas("""
        {"permission_type":"End User","roles":[{"role":"FREP_EDITOR"}]}""");
    cssHas("FREP_EDITOR", "FAM:LABEL:FREP_EDITOR:Editor");

    assertThat(roleNameOf(history().get(0))).isEqualTo("Editor");
  }

  @Test
  void leavesTheCodeUntouchedBesideIt() {
    // The code is what an application authorises on and what the trail is a
    // record of. The name is an addition, never a replacement.
    trailHas("""
        {"permission_type":"End User","roles":[{"role":"FREP_EDITOR"}]}""");
    cssHas("FREP_EDITOR", "FAM:LABEL:FREP_EDITOR:Editor");

    assertThat(history().get(0).privilegeDetails().roles().get(0).role())
        .isEqualTo("FREP_EDITOR");
  }

  @Test
  void givesNoNameToARoleThatHasNoSidecar() {
    trailHas("""
        {"permission_type":"End User","roles":[{"role":"FREP_EDITOR"}]}""");
    cssHas("FREP_EDITOR");

    // Null rather than the code: the screen decides what to show when there is
    // no name, and it cannot tell an absent name from one that happens to look
    // like a code.
    assertThat(roleNameOf(history().get(0))).isNull();
  }

  @Test
  void givesNoNameToARoleDeletedSinceTheChange() {
    // The trail outlives the role. Nothing in CSS answers for it any more.
    trailHas("""
        {"permission_type":"End User","roles":[{"role":"FREP_GONE"}]}""");
    cssHas("FREP_EDITOR", "FAM:LABEL:FREP_EDITOR:Editor");

    assertThat(roleNameOf(history().get(0))).isNull();
  }

  @Test
  void stillReturnsTheHistoryWhenCssCannotBeReached() {
    // A name is a nicety; the trail is not. Failing the whole request to add
    // one would lose the record over its decoration.
    trailHas("""
        {"permission_type":"End User","roles":[{"role":"FREP_EDITOR"}]}""");
    when(cssApiService.getRoles(anyInt(), anyString()))
        .thenThrow(new RuntimeException("CSS is down"));

    List<PermissionAuditHistoryDto> rows = history();

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).privilegeDetails().roles().get(0).role())
        .isEqualTo("FREP_EDITOR");
    assertThat(roleNameOf(rows.get(0))).isNull();
  }

  @Test
  void looksUpOnceForTheWholePageRatherThanOncePerRow() {
    trailHas("""
        {"permission_type":"End User","roles":[{"role":"FREP_EDITOR"}]}""");
    cssHas("FAM:LABEL:FREP_EDITOR:Editor");

    history();

    org.mockito.Mockito.verify(cssApiService, org.mockito.Mockito.times(1))
        .getRoles(INTEGRATION, ENV);
  }
}
