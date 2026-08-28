package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
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
import ca.bc.gov.nrs.fam.security.AuditUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
    service = new PermissionAuditService(
        auditRepository, mapper, cssApiService);
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

  // ---------------------------------------------------------------------------
  // Role names the trail recorded for itself
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("keeps the role name the row recorded, even for a role since deleted")
  void prefersTheRecordedRoleName() {
    /*
        A role's name lives on a sidecar role in CSS, and deleting a role takes
        the sidecar with it. Resolving names on read therefore lost them for
        exactly the roles most worth reading about - the deleted ones.
    */
    trailHas("""
        {"permission_type":"End User","roles":[\
        {"role":"FREP_EDITOR","role_display_name":"Editor"}]}""");
    // The role and its label sidecar are both gone from CSS.
    cssHas();

    assertThat(roleNameOf(history().get(0))).isEqualTo("Editor");
  }

  @Test
  @DisplayName("names FAM's own administrative roles, which carry no sidecar")
  void namesAdministrativeRoles() {
    /*
        They are created on FAM's integration as administrators are appointed,
        not defined on the Manage roles screen, so there is nothing in CSS to
        read a name from - and the trail was showing DEVOPS_ADMIN_6538_DEV where
        an appointment belonged.
    */
    trailHas("""
        {"permission_type":"End User","roles":[{"role":"DEVOPS_ADMIN_6538_DEV"}]}""");
    cssHas();

    assertThat(roleNameOf(history().get(0))).isEqualTo("DevOps administrator");
  }

  @Test
  @DisplayName("leaves an application's own unnamed role as its code")
  void leavesAnUnnamedApplicationRoleAlone() {
    // One added directly in the CSS console, with no sidecar. The code is the
    // honest answer - inventing a name for it would be worse.
    trailHas("""
        {"permission_type":"End User","roles":[{"role":"REPT_ADMIN"}]}""");
    cssHas("REPT_ADMIN");

    assertThat(roleNameOf(history().get(0))).isNull();
  }

  @Test
  @DisplayName("falls back to CSS for a row written before names were recorded")
  void namesAnOlderRowFromCss() {
    // Those rows read exactly as they did before, which is the point of keeping
    // the lookup rather than replacing it.
    trailHas("""
        {"permission_type":"End User","roles":[{"role":"FREP_EDITOR"}]}""");
    cssHas("FREP_EDITOR", "FAM:LABEL:FREP_EDITOR:Editor");

    assertThat(roleNameOf(history().get(0))).isEqualTo("Editor");
  }

  // ---------------------------------------------------------------------------
  // Who has history in an application
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("the people with history in an application")
  class UsersWithHistory {

    private Object[] row(String targetUser, LocalDateTime when, String detailsJson) {
      return new Object[] {targetUser, when, detailsJson};
    }

    private String details(String username, String first, String last) {
      return """
          {"user_guid":"ABC123","username":"%s","first_name":"%s","last_name":"%s"}"""
          .formatted(username, first, last);
    }

    @Test
    @DisplayName("names each person from the snapshot the trail took")
    void namesFromTheSnapshot() {
      // Not resolved now: a person renamed or removed since still reads as they
      // did at the time, which is the point of recording it.
      when(auditRepository.findTargetUsersForApplication(INTEGRATION, ENV))
          .thenReturn(List.<Object[]>of(
              row("IDIR\\ABC123", LocalDateTime.now(), details("JSMITH", "Jane", "Smith"))));

      assertThat(service.getUsersWithHistory(INTEGRATION, ENV))
          .singleElement()
          .satisfies(user -> {
            assertThat(user.targetUserGuid()).isEqualTo("ABC123");
            assertThat(user.targetUserType()).isEqualTo(UserType.IDIR);
            assertThat(user.username()).isEqualTo("JSMITH");
            assertThat(user.firstName()).isEqualTo("Jane");
          });
    }

    @Test
    @DisplayName("one row per person, carrying their most recent change")
    void onePerPerson() {
      /*
          Newest first out of the query, so the first row seen for somebody is
          their most recent - and the name shown is the one recorded then, not
          the one recorded the first time anything happened to them.
      */
      when(auditRepository.findTargetUsersForApplication(INTEGRATION, ENV))
          .thenReturn(List.of(
              row("IDIR\\ABC123", LocalDateTime.of(2026, 8, 2, 0, 0),
                  details("JSMITH", "Jane", "Smith-Jones")),
              row("IDIR\\ABC123", LocalDateTime.of(2026, 8, 1, 0, 0),
                  details("JSMITH", "Jane", "Smith"))));

      assertThat(service.getUsersWithHistory(INTEGRATION, ENV))
          .singleElement()
          .satisfies(user -> assertThat(user.lastName()).isEqualTo("Smith-Jones"));
    }

    @Test
    @DisplayName("keeps the order the query gave, which is newest changed first")
    void keepsTheOrder() {
      when(auditRepository.findTargetUsersForApplication(INTEGRATION, ENV))
          .thenReturn(List.of(
              row("IDIR\\BBB", LocalDateTime.of(2026, 8, 2, 0, 0), details("BLEE", "Bob", "Lee")),
              row("IDIR\\AAA", LocalDateTime.of(2026, 8, 1, 0, 0),
                  details("JSMITH", "Jane", "Smith"))));

      assertThat(service.getUsersWithHistory(INTEGRATION, ENV))
          .extracting(user -> user.username())
          .containsExactly("BLEE", "JSMITH");
    }

    @Test
    @DisplayName("leaves out rows that name no person")
    void skipsRowsNamingNobody() {
      // 'system' is what FAM writes when no person is responsible - there is
      // nobody to offer, and a history lookup for it would match nothing.
      when(auditRepository.findTargetUsersForApplication(INTEGRATION, ENV))
          .thenReturn(List.of(
              row("system", LocalDateTime.now(), null),
              row("IDIR\\ABC123", LocalDateTime.now(), details("JSMITH", "Jane", "Smith"))));

      assertThat(service.getUsersWithHistory(INTEGRATION, ENV))
          .singleElement()
          .satisfies(user -> assertThat(user.username()).isEqualTo("JSMITH"));
    }

    @Test
    @DisplayName("still lists somebody whose snapshot will not read")
    void survivesUnreadableDetails() {
      /*
          One unreadable snapshot costs that person their name, not everybody
          else their row - and the GUID is still enough to read their history,
          which is what the list is for.
      */
      when(auditRepository.findTargetUsersForApplication(INTEGRATION, ENV))
          .thenReturn(List.<Object[]>of(
              row("IDIR\\ABC123", LocalDateTime.now(), "{not json")));

      assertThat(service.getUsersWithHistory(INTEGRATION, ENV))
          .singleElement()
          .satisfies(user -> {
            assertThat(user.targetUserGuid()).isEqualTo("ABC123");
            assertThat(user.username()).isNull();
          });
    }
  }
}
