package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.dto.ScopeDto;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRowDto;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    when(userLookupClient.isConfigured()).thenReturn(true);
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
    when(userLookupClient.getIdirDetailByGuid(guid)).thenReturn(
        Optional.of(new UserLookupIdirUserDto(
            true, userId, guid, first, last, userId.toLowerCase() + "@gov.bc.ca")));
  }

  @Test
  @DisplayName("names a user who has never signed in")
  void namesUnsignedInUser() {
    directoryKnows(GUID, "JSMITH", "Jane", "Smith");

    assertThat(service.withResolvedNames(List.of(unnamed(GUID, "R"))))
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

    service.withResolvedNames(List.of(unnamed(GUID, "R")));

    verify(userLookupClient).getIdirDetailByGuid(GUID);
  }

  @Test
  @DisplayName("leaves a row CSS already named alone")
  void leavesNamedRowsAlone() {
    // CSS is the more current source once somebody has signed in, and looking
    // them up again would cost a call per user to change nothing.
    assertThat(service.withResolvedNames(List.of(named())))
        .singleElement()
        .satisfies(row -> assertThat(row.username()).isEqualTo("JSMITH"));

    verify(userLookupClient, never()).getIdirDetailByGuid(anyString());
  }

  @Test
  @DisplayName("looks up each user once however many roles they hold")
  void deduplicatesByGuid() {
    // The listing is one row per user/role pair, so a user with five roles is
    // five rows and one person.
    directoryKnows(GUID, "JSMITH", "Jane", "Smith");

    List<CssUserRoleRowDto> enriched = service.withResolvedNames(
        List.of(unnamed(GUID, "R1"), unnamed(GUID, "R2"), unnamed(GUID, "R3")));

    verify(userLookupClient, times(1)).getIdirDetailByGuid(GUID);
    assertThat(enriched).allSatisfy(row -> assertThat(row.firstName()).isEqualTo("Jane"));
  }

  @Test
  @DisplayName("keeps the role and scope of every row it names")
  void preservesRoleAndScope() {
    directoryKnows(GUID, "JSMITH", "Jane", "Smith");

    CssUserRoleRowDto scoped = new CssUserRoleRowDto(
        GUID.toLowerCase() + "@azureidir", GUID, "IDIR", null, null, null,
        "CHR_FREP_EDITOR", "Submitter (CHR)", List.of(new ScopeDto("DISTRICT", "DCC", null)), null);

    assertThat(service.withResolvedNames(List.of(scoped)))
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
    when(userLookupClient.getIdirDetailByGuid(anyString()))
        .thenThrow(new UpstreamException(
            org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout",
            "timed out", "user-lookup-api"));

    assertThat(service.withResolvedNames(List.of(unnamed(GUID, "R"))))
        .singleElement()
        .satisfies(row -> assertThat(row.username()).isEqualTo(GUID.toLowerCase() + "@azureidir"));
  }

  @Test
  @DisplayName("stops after the first failure instead of retrying every user")
  void stopsAfterFirstFailure() {
    // One failure is enough to know the rest will fail the same way, and this
    // runs while somebody waits for a table.
    when(userLookupClient.getIdirDetailByGuid(anyString()))
        .thenThrow(new IllegalStateException("down"));

    service.withResolvedNames(List.of(
        unnamed("AAAA1111", "R"), unnamed("BBBB2222", "R"), unnamed("CCCC3333", "R")));

    verify(userLookupClient, times(1)).getIdirDetailByGuid(anyString());
  }

  @Test
  @DisplayName("leaves a row the directory does not recognise as it was")
  void leavesUnknownUserAsIs() {
    when(userLookupClient.getIdirDetailByGuid(anyString())).thenReturn(Optional.empty());

    assertThat(service.withResolvedNames(List.of(unnamed(GUID, "R"))))
        .singleElement()
        .satisfies(row -> assertThat(row.username()).isEqualTo(GUID.toLowerCase() + "@azureidir"));
  }

  @Test
  @DisplayName("does not call the directory when it is not configured")
  void skipsWhenUnconfigured() {
    when(userLookupClient.isConfigured()).thenReturn(false);

    assertThat(service.withResolvedNames(List.of(unnamed(GUID, "R")))).hasSize(1);
    verify(userLookupClient, never()).getIdirDetailByGuid(anyString());
  }

  @Test
  @DisplayName("does not resolve BCeID rows")
  void skipsBceidRows() {
    CssUserRoleRowDto bceid = new CssUserRoleRowDto(
        "abc@bceidbusiness", "ABC", "BCEID", null, null, null, "R", null, List.of(), null);

    service.withResolvedNames(List.of(bceid));

    verify(userLookupClient, never()).getIdirDetailByGuid(anyString());
  }

  @Test
  @DisplayName("bounds how many users one listing resolves")
  void boundsLookupsPerListing() {
    // Each is a separate call to a SOAP-backed directory; a large backlog of
    // never-signed-in users must not turn one page load into hundreds.
    when(userLookupClient.getIdirDetailByGuid(anyString())).thenReturn(Optional.empty());

    List<CssUserRoleRowDto> rows = IntStream.range(0, 40)
        .mapToObj(i -> unnamed("GUID%02d".formatted(i), "R"))
        .toList();

    assertThat(service.withResolvedNames(rows)).hasSize(40);
    verify(userLookupClient, times(25)).getIdirDetailByGuid(anyString());
  }
}
