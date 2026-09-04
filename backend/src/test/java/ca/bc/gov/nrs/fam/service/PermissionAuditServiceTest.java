package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.PrivilegeChangeType;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.CssRoleDto;
import ca.bc.gov.nrs.fam.dto.PermissionAuditHistoryDto;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeAudit;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeType;
import ca.bc.gov.nrs.fam.constants.DirectoryEnv;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeChangeTargetDto;
import ca.bc.gov.nrs.fam.integration.CssApiService;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
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
  @Mock private UserLookupClient userLookupClient;
  @Mock private ApiInstanceEnvResolver apiInstanceEnvResolver;
  @Mock private PermissionAuditWriteService auditWriteService;

  private PermissionAuditService service;

  @BeforeEach
  void setUp() {
    // The application's own mapper: the JSONB was written snake_case, so
    // reading it with a default mapper would silently produce empty details.
    ObjectMapper mapper = new ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    service = new PermissionAuditService(
        auditRepository, mapper, cssApiService, userLookupClient, apiInstanceEnvResolver,
        auditWriteService);
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
    @DisplayName("lists a Business BCeID target, whose key is stored by code")
    void listsBceidTargets() {
      /*
          AuditUser writes userType.getCode(), so a BCeID row is
          "BCEID_BUS\\<guid>" - and this was read back with valueOf, which looks
          for a constant named BCEID_BUS and does not find one. Every BCeID
          target parsed as null and was dropped as a row naming nobody.

          IDIR survived on a coincidence: its code and its constant name are the
          same string, and every fixture here used it. So the list quietly held
          IDIR users only, and a BCeID administrator - who may grant to BCeID
          users alone - was told nothing had ever been recorded.
      */
      when(auditRepository.findTargetUsersForApplication(INTEGRATION, ENV))
          .thenReturn(List.<Object[]>of(
              row("BCEID_BUS\\ABC123", LocalDateTime.now(),
                  details("MVilleneuve3", "Marco", "Villeneuve"))));

      assertThat(service.getUsersWithHistory(INTEGRATION, ENV))
          .singleElement()
          .satisfies(user -> {
            assertThat(user.targetUserType()).isEqualTo(UserType.BCEID);
            assertThat(user.targetUserGuid()).isEqualTo("ABC123");
            assertThat(user.username()).isEqualTo("MVilleneuve3");
          });
    }

    @Test
    @DisplayName("lists both directories together, ordered as the query gave them")
    void listsBothDirectories() {
      // The mix is the realistic case and the one that showed the bug as "half
      // the users are missing" rather than "the page is broken".
      when(auditRepository.findTargetUsersForApplication(INTEGRATION, ENV))
          .thenReturn(List.of(
              row("BCEID_BUS\\BBB", LocalDateTime.of(2026, 8, 2, 0, 0),
                  details("MVilleneuve3", "Marco", "Villeneuve")),
              row("IDIR\\AAA", LocalDateTime.of(2026, 8, 1, 0, 0),
                  details("JSMITH", "Jane", "Smith"))));

      assertThat(service.getUsersWithHistory(INTEGRATION, ENV))
          .extracting(user -> user.targetUserType())
          .containsExactly(UserType.BCEID, UserType.IDIR);
    }

    @Test
    @DisplayName("names somebody the trail could not name, from the directory")
    void namesTheUnnamedFromTheDirectory() {
      /*
          Legacy recorded identity details for the performer of a change and
          nothing for its target, so anybody who only ever had access granted to
          them arrived here as a username and a GUID. The list answers "who are
          these people" rather than "what did the record say at the time", so a
          name is the right answer to it wherever one can be had.
      */
      when(apiInstanceEnvResolver.resolveDirectory(ENV)).thenReturn(DirectoryEnv.DEV);
      when(userLookupClient.getIdirDetailByGuid(DirectoryEnv.DEV, "ABC123"))
          .thenReturn(java.util.Optional.of(new UserLookupIdirUserDto(
              true, "BTURNER", "ABC123", "Bob", "Turner", "bob@gov.bc.ca")));

      when(auditRepository.findTargetUsersForApplication(INTEGRATION, ENV))
          .thenReturn(List.<Object[]>of(
              row("IDIR\\ABC123", LocalDateTime.now(),
                  "{\"user_guid\":\"ABC123\",\"username\":\"BTURNER\"}")));

      assertThat(service.getUsersWithHistory(INTEGRATION, ENV))
          .singleElement()
          .satisfies(user -> {
            assertThat(user.firstName()).isEqualTo("Bob");
            assertThat(user.email()).isEqualTo("bob@gov.bc.ca");
            assertThat(user.username()).isEqualTo("BTURNER");
          });
    }

    @Test
    @DisplayName("takes a name from another application's trail before asking the directory")
    void prefersWhatTheTrailAlreadyKnows() {
      /*
          FAM snapshots identity every time it records a change, so a person
          unnamed in one application is often named in another. That record is
          contemporaneous rather than a present-day answer, costs one query
          instead of a call per person, and still answers for somebody who has
          since left - which the directory would not.
      */
      when(auditRepository.findKnownIdentities(anyCollection()))
          .thenReturn(List.<Object[]>of(new Object[]{"ABC123",
              "{\"user_guid\":\"ABC123\",\"username\":\"BTURNER\","
              + "\"first_name\":\"Bob\",\"last_name\":\"Turner\","
              + "\"email\":\"bob@gov.bc.ca\"}"}));
      when(auditRepository.findTargetUsersForApplication(INTEGRATION, ENV))
          .thenReturn(List.<Object[]>of(
              row("IDIR\\ABC123", LocalDateTime.now(),
                  "{\"user_guid\":\"ABC123\",\"username\":\"BTURNER\"}")));

      assertThat(service.getUsersWithHistory(INTEGRATION, ENV))
          .singleElement()
          .satisfies(user -> assertThat(user.email()).isEqualTo("bob@gov.bc.ca"));

      // The directory is never troubled for somebody the trail can already name.
      verifyNoInteractions(userLookupClient);
      verify(auditWriteService).cacheTargetDetails(eq("IDIR\\ABC123"), any());
    }

    @Test
    @DisplayName("writes a looked-up name back, so the next visit does not pay for it")
    void cachesWhatItLookedUp() {
      /*
          The lookup costs a directory call. Without writing it back it would
          cost one on every visit to this screen, for the same person, forever.
          The statement fills only rows that carry no name, so nothing recorded
          at the time of a change is disturbed.
      */
      when(apiInstanceEnvResolver.resolveDirectory(ENV)).thenReturn(DirectoryEnv.DEV);
      when(userLookupClient.getIdirDetailByGuid(DirectoryEnv.DEV, "ABC123"))
          .thenReturn(java.util.Optional.of(new UserLookupIdirUserDto(
              true, "BTURNER", "ABC123", "Bob", "Turner", "bob@gov.bc.ca")));
      when(auditRepository.findTargetUsersForApplication(INTEGRATION, ENV))
          .thenReturn(List.<Object[]>of(
              row("IDIR\\ABC123", LocalDateTime.now(),
                  "{\"user_guid\":\"ABC123\",\"username\":\"BTURNER\"}")));

      service.getUsersWithHistory(INTEGRATION, ENV);

      org.mockito.ArgumentCaptor<PrivilegeChangeTargetDto> captor =
          org.mockito.ArgumentCaptor.forClass(PrivilegeChangeTargetDto.class);
      verify(auditWriteService).cacheTargetDetails(eq("IDIR\\ABC123"), captor.capture());
      assertThat(captor.getValue().email()).isEqualTo("bob@gov.bc.ca");
      assertThat(captor.getValue().firstName()).isEqualTo("Bob");
    }

    @Test
    @DisplayName("writes nothing back for somebody the trail already names")
    void cachesNothingWhenAlreadyNamed() {
      when(apiInstanceEnvResolver.resolveDirectory(ENV)).thenReturn(DirectoryEnv.DEV);
      when(auditRepository.findTargetUsersForApplication(INTEGRATION, ENV))
          .thenReturn(List.<Object[]>of(
              row("IDIR\\ABC123", LocalDateTime.now(), details("JSMITH", "Jane", "Smith"))));

      service.getUsersWithHistory(INTEGRATION, ENV);

      verifyNoInteractions(auditWriteService);
    }

    @Test
    @DisplayName("leaves a recorded name alone, even when the directory disagrees")
    void theSnapshotWins() {
      /*
          The whole point of snapshotting is that a person renamed since still
          reads as they did at the time. Only the gaps are filled - a row that
          carries a name is never re-resolved.
      */
      when(apiInstanceEnvResolver.resolveDirectory(ENV)).thenReturn(DirectoryEnv.DEV);
      when(auditRepository.findTargetUsersForApplication(INTEGRATION, ENV))
          .thenReturn(List.<Object[]>of(
              row("IDIR\\ABC123", LocalDateTime.now(), details("JSMITH", "Jane", "Smith"))));

      assertThat(service.getUsersWithHistory(INTEGRATION, ENV))
          .singleElement()
          .satisfies(user -> assertThat(user.firstName()).isEqualTo("Jane"));

      verifyNoInteractions(userLookupClient);
    }

    @Test
    @DisplayName("shows the list without names when the directory cannot be reached")
    void aDirectoryOutageCostsNamesNotTheScreen() {
      // The list is already correct and complete without them.
      when(apiInstanceEnvResolver.resolveDirectory(ENV)).thenReturn(DirectoryEnv.DEV);
      when(userLookupClient.getIdirDetailByGuid(any(), anyString()))
          .thenThrow(new RuntimeException("directory unreachable"));

      when(auditRepository.findTargetUsersForApplication(INTEGRATION, ENV))
          .thenReturn(List.<Object[]>of(
              row("IDIR\\ABC123", LocalDateTime.now(),
                  "{\"user_guid\":\"ABC123\",\"username\":\"BTURNER\"}")));

      assertThat(service.getUsersWithHistory(INTEGRATION, ENV))
          .singleElement()
          .satisfies(user -> {
            assertThat(user.username()).isEqualTo("BTURNER");
            assertThat(user.firstName()).isNull();
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
