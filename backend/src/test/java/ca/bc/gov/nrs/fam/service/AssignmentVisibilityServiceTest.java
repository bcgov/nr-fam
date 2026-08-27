package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRowDto;
import ca.bc.gov.nrs.fam.dto.UserLookupBceidUserDto;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

/**
 * What a requester may see of an application's assignments.
 *
 * <p>A Business BCeID administrator is external: they see only BCeID users from
 * their own organisation. Everything here is about that rule holding on the
 * <em>response</em>, not on what the UI chooses to render.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AssignmentVisibilityService")
class AssignmentVisibilityServiceTest {

  private static final String OWN_ORG = "AAAA0000BUSINESS";
  private static final String OTHER_ORG = "BBBB1111BUSINESS";

  @Mock private UserLookupClient userLookupClient;
  @Mock private AssignmentRowEnrichmentService enrichmentService;
  @InjectMocks private AssignmentVisibilityService service;

  private static Requester bceidAdmin(String businessGuid) {
    return Requester.builder()
        .userName("BCEID_ADMIN").userType(UserType.BCEID).businessGuid(businessGuid)
        .accessRoles(List.of("DELEGATED_ADMIN_1_DEV")).build();
  }

  private static Requester idirAdmin() {
    return Requester.builder()
        .userName("JSMITH").userType(UserType.IDIR).accessRoles(List.of("FAM_ADMIN")).build();
  }

  private static CssUserRoleRowDto bceidRow(String guid, String roleName) {
    return new CssUserRoleRowDto(
        guid.toLowerCase() + "@bceidbusiness", guid, "BCEID", null, null, null, roleName, null, List.of(), null);
  }

  private static CssUserRoleRowDto idirRow() {
    return new CssUserRoleRowDto(
        "JSMITH", "AAAA9999", "IDIR", "Jane", "Smith", "jane@gov.bc.ca", "R", null, List.of(), null);
  }

  private void directoryReports(String guid, String organization) {
    when(userLookupClient.getBusinessBceid(any(), org.mockito.ArgumentMatchers.eq(guid)))
        .thenReturn(Optional.of(new UserLookupBceidUserDto(
            true, "TARGET" + guid, guid, organization, "Example Forestry Ltd",
            "Jane", "Smith", "jane@example.com")));
  }

  @Test
  @DisplayName("shows a BCeID administrator their own organisation's users")
  void showsOwnOrganization() {
    directoryReports("AAA1", OWN_ORG);

    assertThat(service.visibleTo(bceidAdmin(OWN_ORG), List.of(bceidRow("AAA1", "R"))))
        .singleElement()
        .satisfies(row -> assertThat(row.username()).isEqualTo("TARGETAAA1"));
  }

  @Test
  @DisplayName("hides users from another organisation")
  void hidesOtherOrganizations() {
    // The gap this closes: the grant path already refused them, but they could
    // still be read off the table.
    directoryReports("AAA1", OWN_ORG);
    directoryReports("BBB2", OTHER_ORG);

    assertThat(service.visibleTo(
        bceidAdmin(OWN_ORG), List.of(bceidRow("AAA1", "R"), bceidRow("BBB2", "R"))))
        .hasSize(1)
        .allSatisfy(row -> assertThat(row.username()).isEqualTo("TARGETAAA1"));
  }

  @Test
  @DisplayName("hides IDIR users from a BCeID administrator, without a lookup")
  void hidesIdirUsers() {
    // An IDIR user belongs to no business, so none can match - and dropping them
    // before resolving anything is what keeps the cost proportionate.
    assertThat(service.visibleTo(bceidAdmin(OWN_ORG), List.of(idirRow()))).isEmpty();

    verify(userLookupClient, never()).getBusinessBceid(any(), anyString());
  }

  @Test
  @DisplayName("names the rows it shows, from the same lookup that filtered them")
  void namesTheRowsItShows() {
    // The directory answer carries the organisation and the name, so BCeID rows
    // are named here rather than by a second pass.
    directoryReports("AAA1", OWN_ORG);

    assertThat(service.visibleTo(bceidAdmin(OWN_ORG), List.of(bceidRow("AAA1", "R"))))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.firstName()).isEqualTo("Jane");
          assertThat(row.email()).isEqualTo("jane@example.com");
        });
  }

  @Test
  @DisplayName("keeps the role's description on the rows it shows")
  void keepsRoleDescription() {
    // Filtering rebuilds the row to add the name; the label the table shows
    // must not be lost on the way through.
    directoryReports("AAA1", OWN_ORG);
    CssUserRoleRowDto described = new CssUserRoleRowDto(
        "aaa1@bceidbusiness", "AAA1", "BCEID", null, null, null,
        "FOM_SUBMITTER", "Submitter", List.of(), null);

    assertThat(service.visibleTo(bceidAdmin(OWN_ORG), List.of(described)))
        .singleElement()
        .satisfies(row -> assertThat(row.roleDisplayName()).isEqualTo("Submitter"));
  }

  @Test
  @DisplayName("looks each user up once however many roles they hold")
  void resolvesEachUserOnce() {
    directoryReports("AAA1", OWN_ORG);

    assertThat(service.visibleTo(bceidAdmin(OWN_ORG),
        List.of(bceidRow("AAA1", "R1"), bceidRow("AAA1", "R2"), bceidRow("AAA1", "R3"))))
        .hasSize(3);

    verify(userLookupClient, times(1)).getBusinessBceid(any(), anyString());
  }

  @Test
  @DisplayName("compares organisations regardless of case")
  void comparesCaseInsensitively() {
    directoryReports("AAA1", OWN_ORG.toLowerCase());

    assertThat(service.visibleTo(bceidAdmin(OWN_ORG), List.of(bceidRow("AAA1", "R"))))
        .hasSize(1);
  }

  @Test
  @DisplayName("hides a user with no organisation recorded")
  void hidesUserWithNoOrganization() {
    // An unknown organisation is not a matching one.
    directoryReports("AAA1", null);

    assertThat(service.visibleTo(bceidAdmin(OWN_ORG), List.of(bceidRow("AAA1", "R")))).isEmpty();
  }

  @Test
  @DisplayName("hides a user the directory does not recognise")
  void hidesUnknownUser() {
    when(userLookupClient.getBusinessBceid(any(), anyString())).thenReturn(Optional.empty());

    assertThat(service.visibleTo(bceidAdmin(OWN_ORG), List.of(bceidRow("AAA1", "R")))).isEmpty();
  }

  @Test
  @DisplayName("raises a directory failure rather than returning a short list")
  void directoryFailureIsRaised() {
    // A BCeID administrator shown a silently shortened list would conclude those
    // users have no access, and act on it.
    when(userLookupClient.getBusinessBceid(any(), anyString()))
        .thenThrow(new UpstreamException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout",
            "timed out", "user-lookup-api"));

    assertThatThrownBy(() ->
        service.visibleTo(bceidAdmin(OWN_ORG), List.of(bceidRow("AAA1", "R"))))
        .isInstanceOf(UpstreamException.class);
  }

  @Test
  @DisplayName("refuses when the administrator's own organisation is unknown")
  void refusesWhenOwnOrganizationUnknown() {
    // Answering with an empty table would read as "nobody has access", which is
    // a different and misleading statement.
    assertThatThrownBy(() ->
        service.visibleTo(bceidAdmin(null), List.of(bceidRow("AAA1", "R"))))
        .isInstanceOf(FamHttpException.class);
  }

  @Test
  @DisplayName("leaves an IDIR administrator's listing whole")
  void idirAdministratorSeesEverything() {
    List<CssUserRoleRowDto> rows = List.of(idirRow(), bceidRow("AAA1", "R"));
    when(enrichmentService.withResolvedNames(rows)).thenReturn(rows);

    assertThat(service.visibleTo(idirAdmin(), rows)).hasSize(2);
    verify(userLookupClient, never()).getBusinessBceid(any(), anyString());
  }

  @Test
  @DisplayName("names an IDIR administrator's rows through the enrichment pass")
  void idirAdministratorStillGetsNames() {
    List<CssUserRoleRowDto> rows = List.of(idirRow());
    service.visibleTo(idirAdmin(), rows);

    verify(enrichmentService).withResolvedNames(rows);
  }
}
