package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.DirectoryEnv;
import ca.bc.gov.nrs.fam.dto.ScopeDto;
import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import ca.bc.gov.nrs.fam.dto.CssAdministratorRowDto;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRowDto;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Naming the users CSS could only identify by GUID.
 *
 * <p>The rule throughout: the assignments are already correct, so nothing here
 * may cost the caller the table.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AssignmentRowEnrichmentService")
class AssignmentRowEnrichmentServiceTest {

  private static final String GUID = "0A1B2C3D4E5F60718293A4B5C6D7E8F9";

  @Mock private UserLookupClient userLookupClient;
  @InjectMocks private AssignmentRowEnrichmentService service;

  @BeforeEach
  void directoryIsAvailable() {
    when(userLookupClient.isConfigured(any())).thenReturn(true);
  }

  /** A user CSS has never seen sign in: a username and nothing else. */
  private static CssUserRoleRowDto unnamed(String guid, String roleName) {
    return new CssUserRoleRowDto(
        guid.toLowerCase() + "@azureidir", guid, "IDIR", null, null, null, roleName, null, List.of(), null);
  }

  private static CssUserRoleRowDto named() {
    return new CssUserRoleRowDto(
        "JSMITH", "AAAA9999", "IDIR", "Jane", "Smith", "jane@gov.bc.ca", "R", null, List.of(), null);
  }

  private void directoryKnows(String guid, String userId, String first, String last) {
    when(userLookupClient.getIdirDetailByGuid(any(), eq(guid))).thenReturn(
        Optional.of(new UserLookupIdirUserDto(
            true, userId, guid, first, last, userId.toLowerCase() + "@gov.bc.ca")));
  }

  @Test
  @DisplayName("names a user who has never signed in")
  void namesUnsignedInUser() {
    directoryKnows(GUID, "JSMITH", "Jane", "Smith");

    assertThat(service.withResolvedNames(DirectoryEnv.TEST, List.of(unnamed(GUID, "R"))))
        .singleElement()
        .satisfies(row -> {
          // The user id is what an administrator recognises, not the GUID.
          assertThat(row.username()).isEqualTo("JSMITH");
          assertThat(row.firstName()).isEqualTo("Jane");
          assertThat(row.lastName()).isEqualTo("Smith");
          assertThat(row.email()).isEqualTo("jsmith@gov.bc.ca");
        });
  }

  @Test
  @DisplayName("normalises the GUID case before looking it up")
  void upperCasesTheGuid() {
    // The directory matches either case, so this is not for its benefit: CSS
    // reports the username lower case and FAM stores GUIDs upper case, and
    // normalising is what makes the deduplication below reliable.
    directoryKnows(GUID, "JSMITH", "Jane", "Smith");

    service.withResolvedNames(DirectoryEnv.TEST, List.of(unnamed(GUID, "R")));

    verify(userLookupClient).getIdirDetailByGuid(any(), eq(GUID));
  }

  @Test
  @DisplayName("leaves a row CSS already named alone")
  void leavesNamedRowsAlone() {
    // CSS is the more current source once somebody has signed in, and looking
    // them up again would cost a call per user to change nothing.
    assertThat(service.withResolvedNames(DirectoryEnv.TEST, List.of(named())))
        .singleElement()
        .satisfies(row -> assertThat(row.username()).isEqualTo("JSMITH"));

    verify(userLookupClient, never()).getIdirDetailByGuid(any(), anyString());
  }

  @Test
  @DisplayName("looks up each user once however many roles they hold")
  void deduplicatesByGuid() {
    // The listing is one row per user/role pair, so a user with five roles is
    // five rows and one person.
    directoryKnows(GUID, "JSMITH", "Jane", "Smith");

    List<CssUserRoleRowDto> enriched = service.withResolvedNames(DirectoryEnv.TEST, 
        List.of(unnamed(GUID, "R1"), unnamed(GUID, "R2"), unnamed(GUID, "R3")));

    verify(userLookupClient, times(1)).getIdirDetailByGuid(any(), eq(GUID));
    assertThat(enriched).allSatisfy(row -> assertThat(row.firstName()).isEqualTo("Jane"));
  }

  @Test
  @DisplayName("keeps the role and scope of every row it names")
  void preservesRoleAndScope() {
    directoryKnows(GUID, "JSMITH", "Jane", "Smith");

    CssUserRoleRowDto scoped = new CssUserRoleRowDto(
        GUID.toLowerCase() + "@azureidir", GUID, "IDIR", null, null, null,
        "CHR_FREP_EDITOR", "Submitter (CHR)", List.of(new ScopeDto("DISTRICT", "DCC", null)), null);

    assertThat(service.withResolvedNames(DirectoryEnv.TEST, List.of(scoped)))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.roleName()).isEqualTo("CHR_FREP_EDITOR");
          // Naming the user rebuilds the row; everything about the role has to
          // survive that, including what the table labels it with.
          assertThat(row.roleDisplayName()).isEqualTo("Submitter (CHR)");
          assertThat(row.scopes())
              .containsExactly(new ScopeDto("DISTRICT", "DCC", null));
        });
  }

  @Test
  @DisplayName("returns the rows unchanged when the directory is unreachable")
  void survivesDirectoryFailure() {
    // The assignments are already known and correct. An outage costs a few
    // names, not the table.
    when(userLookupClient.getIdirDetailByGuid(any(), anyString()))
        .thenThrow(new UpstreamException(
            org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout",
            "timed out", "user-lookup-api"));

    assertThat(service.withResolvedNames(DirectoryEnv.TEST, List.of(unnamed(GUID, "R"))))
        .singleElement()
        .satisfies(row -> assertThat(row.username()).isEqualTo(GUID.toLowerCase() + "@azureidir"));
  }

  @Test
  @DisplayName("stops after the first failure instead of retrying every user")
  void stopsAfterFirstFailure() {
    // One failure is enough to know the rest will fail the same way, and this
    // runs while somebody waits for a table.
    when(userLookupClient.getIdirDetailByGuid(any(), anyString()))
        .thenThrow(new IllegalStateException("down"));

    service.withResolvedNames(DirectoryEnv.TEST, List.of(
        unnamed("AAAA1111", "R"), unnamed("BBBB2222", "R"), unnamed("CCCC3333", "R")));

    verify(userLookupClient, times(1)).getIdirDetailByGuid(any(), anyString());
  }

  @Test
  @DisplayName("leaves a row the directory does not recognise as it was")
  void leavesUnknownUserAsIs() {
    when(userLookupClient.getIdirDetailByGuid(any(), anyString())).thenReturn(Optional.empty());

    assertThat(service.withResolvedNames(DirectoryEnv.TEST, List.of(unnamed(GUID, "R"))))
        .singleElement()
        .satisfies(row -> assertThat(row.username()).isEqualTo(GUID.toLowerCase() + "@azureidir"));
  }

  @Test
  @DisplayName("does not call the directory when it is not configured")
  void skipsWhenUnconfigured() {
    when(userLookupClient.isConfigured(any())).thenReturn(false);

    assertThat(service.withResolvedNames(DirectoryEnv.TEST, List.of(unnamed(GUID, "R")))).hasSize(1);
    verify(userLookupClient, never()).getIdirDetailByGuid(any(), anyString());
  }

  @Test
  @DisplayName("does not resolve BCeID rows")
  void skipsBceidRows() {
    CssUserRoleRowDto bceid = new CssUserRoleRowDto(
        "abc@bceidbusiness", "ABC", "BCEID", null, null, null, "R", null, List.of(), null);

    service.withResolvedNames(DirectoryEnv.TEST, List.of(bceid));

    verify(userLookupClient, never()).getIdirDetailByGuid(any(), anyString());
  }

  @Test
  @DisplayName("names every user a bulk upload granted")
  void namesAWholeUpload() {
    // The bug: the cap was 25, and a 52-person upload is 52 people who have
    // never signed in. The 26th onward showed <guid>@azureidir with no name.
    List<CssUserRoleRowDto> rows = IntStream.range(0, 52)
        .mapToObj(i -> {
          String guid = "GUID%02d".formatted(i);
          directoryKnows(guid, "USER%02d".formatted(i), "First" + i, "Last" + i);
          // Two roles each, as the upload file had.
          return List.of(unnamed(guid, "R1"), unnamed(guid, "R2"));
        })
        .flatMap(List::stream)
        .toList();

    assertThat(service.withResolvedNames(DirectoryEnv.TEST, rows))
        .hasSize(104)
        .allSatisfy(row -> {
          assertThat(row.firstName()).isNotBlank();
          assertThat(row.username()).doesNotContain("@azureidir");
        });
    verify(userLookupClient, times(52)).getIdirDetailByGuid(any(), anyString());
  }

  @Test
  @DisplayName("still bounds how many users one listing resolves")
  void boundsLookupsPerListing() {
    // Each is a separate call to a SOAP-backed directory; a large backlog of
    // never-signed-in users must not turn one page load into thousands.
    when(userLookupClient.getIdirDetailByGuid(any(), anyString())).thenReturn(Optional.empty());

    int total = AssignmentRowEnrichmentService.MAX_LOOKUPS + 10;
    List<CssUserRoleRowDto> rows = IntStream.range(0, total)
        .mapToObj(i -> unnamed("GUID%04d".formatted(i), "R"))
        .toList();

    assertThat(service.withResolvedNames(DirectoryEnv.TEST, rows)).hasSize(total);
    verify(userLookupClient, times(AssignmentRowEnrichmentService.MAX_LOOKUPS))
        .getIdirDetailByGuid(any(), anyString());
  }

  @Test
  @DisplayName("reuses a resolved name on the next listing")
  void reusesResolvedNames() {
    // Lifting the cap is only affordable if reloading the tab does not ask the
    // directory about the same fifty people again.
    directoryKnows(GUID, "JSMITH", "Jane", "Smith");

    service.withResolvedNames(DirectoryEnv.TEST, List.of(unnamed(GUID, "R")));
    assertThat(service.withResolvedNames(DirectoryEnv.TEST, List.of(unnamed(GUID, "R"))))
        .singleElement()
        .satisfies(row -> assertThat(row.firstName()).isEqualTo("Jane"));

    verify(userLookupClient, times(1)).getIdirDetailByGuid(any(), eq(GUID));
  }

  @Test
  @DisplayName("asks again about a user the directory did not recognise")
  void doesNotRememberMisses() {
    // Only answers are held. A miss or an outage must not stick for the TTL.
    when(userLookupClient.getIdirDetailByGuid(any(), anyString())).thenReturn(Optional.empty());

    service.withResolvedNames(DirectoryEnv.TEST, List.of(unnamed(GUID, "R")));
    service.withResolvedNames(DirectoryEnv.TEST, List.of(unnamed(GUID, "R")));

    verify(userLookupClient, times(2)).getIdirDetailByGuid(any(), eq(GUID));
  }

  @Test
  @DisplayName("does not reuse a name across directories")
  void cachesPerDirectory() {
    directoryKnows(GUID, "JSMITH", "Jane", "Smith");

    service.withResolvedNames(DirectoryEnv.TEST, List.of(unnamed(GUID, "R")));
    service.withResolvedNames(DirectoryEnv.PROD, List.of(unnamed(GUID, "R")));

    verify(userLookupClient).getIdirDetailByGuid(eq(DirectoryEnv.TEST), eq(GUID));
    verify(userLookupClient).getIdirDetailByGuid(eq(DirectoryEnv.PROD), eq(GUID));
  }

  // ---------------------------------------------------------------------------
  // The administrator listings, which had no enrichment at all
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Administrator rows")
  class AdministratorRows {

    /** An administrator appointed by somebody else, who has never signed in. */
    private CssAdministratorRowDto unnamedAdmin(String guid) {
      return new CssAdministratorRowDto(
          guid.toLowerCase() + "@azureidir", guid, "IDIR", null, null, null,
          AdminRoleAuthGroup.APP_ADMIN, "APP_ADMIN_6538_DEV", null, null, List.of());
    }

    @Test
    @DisplayName("names an administrator CSS could only identify by GUID")
    void namesAnUnnamedAdministrator() {
      /*
          The bug this fixes. CSS learns a person's name when they sign in, and
          an administrator has no particular reason to have signed into the
          application they administer - a bulk upload makes that the normal case.
          The roster showed `<guid>@azureidir` with empty name and email.
      */
      directoryKnows(GUID, "JSMITH", "Jane", "Smith");

      assertThat(service.withResolvedAdminNames(
          DirectoryEnv.TEST, List.of(unnamedAdmin(GUID))))
          .singleElement()
          .satisfies(row -> {
            assertThat(row.username()).isEqualTo("JSMITH");
            assertThat(row.firstName()).isEqualTo("Jane");
            assertThat(row.lastName()).isEqualTo("Smith");
            assertThat(row.email()).isEqualTo("jsmith@gov.bc.ca");
          });
    }

    @Test
    @DisplayName("carries the appointment through untouched")
    void keepsWhatTheAppointmentIs() {
      // The row is rebuilt to correct the name on it. Losing the tier or the
      // delegated role would turn a naming fix into a data loss.
      directoryKnows(GUID, "JSMITH", "Jane", "Smith");

      CssAdministratorRowDto delegated = new CssAdministratorRowDto(
          GUID.toLowerCase() + "@azureidir", GUID, "IDIR", null, null, null,
          AdminRoleAuthGroup.DELEGATED_ADMIN, "DELEGATED_ADMIN_6538_DEV__EDITOR",
          "EDITOR", "Editor", List.of());

      assertThat(service.withResolvedAdminNames(DirectoryEnv.TEST, List.of(delegated)))
          .singleElement()
          .satisfies(row -> {
            assertThat(row.tier()).isEqualTo(AdminRoleAuthGroup.DELEGATED_ADMIN);
            assertThat(row.roleName()).isEqualTo("DELEGATED_ADMIN_6538_DEV__EDITOR");
            assertThat(row.delegatedRoleName()).isEqualTo("EDITOR");
            assertThat(row.delegatedRoleDisplayName()).isEqualTo("Editor");
          });
    }

    @Test
    @DisplayName("leaves an administrator CSS already named alone")
    void leavesNamedAdministratorsAlone() {
      // CSS is the more current source for somebody who has signed in, and a
      // lookup per row would cost a directory call to change nothing.
      CssAdministratorRowDto named = new CssAdministratorRowDto(
          "OLIBERCH", "BBBB2222", "IDIR", "Olga", "Liberchuk", "olga@gov.bc.ca",
          AdminRoleAuthGroup.APP_ADMIN, "APP_ADMIN_6538_DEV", null, null, List.of());

      assertThat(service.withResolvedAdminNames(DirectoryEnv.TEST, List.of(named)))
          .containsExactly(named);
      verify(userLookupClient, never()).getIdirDetailByGuid(any(), any());
    }

    @Test
    @DisplayName("a directory outage costs the names, not the roster")
    void anOutageCostsNamesOnly() {
      when(userLookupClient.getIdirDetailByGuid(any(), any()))
          .thenThrow(new RuntimeException("directory unreachable"));

      assertThat(service.withResolvedAdminNames(
          DirectoryEnv.TEST, List.of(unnamedAdmin(GUID)))).hasSize(1);
    }
  }
}
