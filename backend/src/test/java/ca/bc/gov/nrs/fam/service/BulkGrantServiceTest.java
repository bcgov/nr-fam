package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.EmailSendingStatus;
import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.CssBulkGrantRowDto;
import ca.bc.gov.nrs.fam.dto.CssRoleOptionDto;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentRequest;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentResult;
import ca.bc.gov.nrs.fam.dto.UserLookupBceidUserDto;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BulkGrantService")
class BulkGrantServiceTest {

  private static final int INTEGRATION = 54321;
  private static final String ENV = "dev";
  private static final String GUID = "AABBCCDDEEFF00112233445566778899";

  @Mock private CssIntegrationService cssIntegrationService;
  @Mock private UserLookupClient userLookupClient;
  @Mock private AuthorizationService authorizationService;
  @Mock private ca.bc.gov.nrs.fam.security.TargetOrganizationGuard targetOrganizationGuard;

  private BulkGrantService service;

  private static final Requester UPLOADER = Requester.builder()
      .userName("JSMITH").userGuid("EEEE1111").userType(UserType.IDIR)
      .accessRoles(List.of(FamAdminRole.appAdmin(INTEGRATION, ENV)))
      .build();

  private static CssRoleOptionDto role(String name, boolean district, boolean client) {
    return new CssRoleOptionDto(name, "View All", "Long description", null,
        false, List.of(), district, client);
  }

  @BeforeEach
  void setUp() {
    service = new BulkGrantService(
        cssIntegrationService, userLookupClient, authorizationService, targetOrganizationGuard);

    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("FSPTS_VIEW_ALL", false, false)));

    when(userLookupClient.getIdirDetailByGuid(GUID)).thenReturn(Optional.of(
        new UserLookupIdirUserDto(true, "JANES", GUID, "Jane", "Smith", "jane@gov.bc.ca")));
  }

  private List<CssBulkGrantRowDto> preview(String csv) {
    return service.preview(INTEGRATION, ENV, csv, UPLOADER).rows();
  }

  // ---------------------------------------------------------------------------
  // Resolution - what the confirmation table shows
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("resolves the person's name and the role's display name")
  void resolvesNames() {
    assertThat(preview(GUID + ",FSPTS_VIEW_ALL"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isTrue();
          assertThat(row.firstName()).isEqualTo("Jane");
          assertThat(row.lastName()).isEqualTo("Smith");
          assertThat(row.userName()).isEqualTo("JANES");
          assertThat(row.userType()).isEqualTo(UserType.IDIR);
          // The point of the confirmation: a name, not a code.
          assertThat(row.roleDisplayName()).isEqualTo("View All");
        });
  }

  @Test
  @DisplayName("falls back to Business BCeID when the GUID is not an IDIR one")
  void resolvesBceid() {
    when(userLookupClient.getIdirDetailByGuid(GUID)).thenReturn(Optional.empty());
    when(userLookupClient.getBusinessBceid(UserLookupClient.SearchBy.USER_GUID, GUID))
        .thenReturn(Optional.of(new UserLookupBceidUserDto(true,
            "JSMITH-BCEID", GUID, "BUSGUID", "Acme Forestry", "Jane", "Smith", "j@acme.com")));

    // The file carries no user type, so which directory a GUID is in has to be
    // discovered - and shown, so the uploader can see it was the right person.
    assertThat(preview(GUID + ",FSPTS_VIEW_ALL"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.userType()).isEqualTo(UserType.BCEID);
          assertThat(row.organization()).isEqualTo("Acme Forestry");
        });
  }

  @Test
  @DisplayName("a GUID neither directory knows is an error, not a silent skip")
  void unknownGuid() {
    when(userLookupClient.getIdirDetailByGuid(anyString())).thenReturn(Optional.empty());
    when(userLookupClient.getBusinessBceid(any(), anyString())).thenReturn(Optional.empty());

    assertThat(preview(GUID + ",FSPTS_VIEW_ALL"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isFalse();
          assertThat(row.error()).contains("No IDIR or Business BCeID user");
        });
  }

  // ---------------------------------------------------------------------------
  // What may not be granted this way
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("refuses a role the application does not define")
  void unknownRole() {
    assertThat(preview(GUID + ",NOT_A_ROLE"))
        .singleElement()
        .satisfies(row -> assertThat(row.error()).contains("No role named NOT_A_ROLE"));
  }

  @Test
  @DisplayName("refuses an administrative role")
  void refusesAdminRoles() {
    // Appointing an administrator is not granting access, and doing it by upload
    // would route around the tier rules the admin screens apply.
    for (String adminRole : List.of("FAM_ADMIN", "APP_ADMIN_54321_DEV",
        "DELEGATED_ADMIN_54321_DEV__FSPTS_VIEW_ALL")) {

      assertThat(preview(GUID + "," + adminRole))
          .singleElement()
          .satisfies(row -> {
            assertThat(row.valid()).isFalse();
            assertThat(row.error()).contains("administrative role");
          });
    }
  }

  @Test
  @DisplayName("refuses a scoped role, which two columns cannot express")
  void refusesScopedRoles() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_EDITOR", true, false)));

    // Granting the base role instead would assign something no application
    // authorises on: a scoped grant only ever assigns per-scope roles.
    assertThat(preview(GUID + ",CHR_FREP_EDITOR"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isFalse();
          assertThat(row.error()).contains("district");
        });
  }

  @Test
  @DisplayName("applies the same self-grant rule as the single grant path")
  void refusesSelfGrant() {
    org.mockito.Mockito.doThrow(FamHttpException.forbidden("self_grant_prohibited",
            "Altering permission privilege of self is not allowed."))
        .when(authorizationService).forbidSelfGrant(any(), anyString());

    assertThat(preview(GUID + ",FSPTS_VIEW_ALL"))
        .singleElement()
        .satisfies(row -> assertThat(row.error()).contains("self"));
  }

  @Test
  @DisplayName("applies the delegated administrator's role restriction")
  void refusesUndelegatedRole() {
    org.mockito.Mockito.doThrow(FamHttpException.forbidden("permission_required",
            "You have not been delegated FSPTS_VIEW_ALL in this application."))
        .when(authorizationService).requireGrantableRoles(any(), anyInt(), anyString(), any());

    assertThat(preview(GUID + ",FSPTS_VIEW_ALL"))
        .singleElement()
        .satisfies(row -> assertThat(row.error()).contains("not been delegated"));
  }

  // ---------------------------------------------------------------------------
  // Parsing
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("parses a file a spreadsheet wrote, byte-order mark and all")
  void stripsByteOrderMark() {
    // Excel prefixes a UTF-8 CSV with a BOM. Left in place it becomes part of
    // the first field, and the first row alone fails to resolve - the kind of
    // bug that looks like bad data rather than bad parsing.
    //
    // No header here on purpose: with one, the mark lands on a field that is
    // discarded anyway, so a header row hides the problem rather than exposing
    // it. The mark has to sit directly on a GUID for this to mean anything.
    assertThat(preview("\uFEFF" + GUID + ",FSPTS_VIEW_ALL"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isTrue();
          assertThat(row.userGuid()).isEqualTo(GUID);
        });
  }

  @Test
  @DisplayName("parses the downloadable template once rows are added")
  void parsesTheTemplate() {
    // The template the screen hands out is exactly this header.
    assertThat(preview("user_guid,role\n" + GUID + ",FSPTS_VIEW_ALL"))
        .singleElement()
        .satisfies(row -> assertThat(row.valid()).isTrue());
  }

  @Test
  @DisplayName("skips a header row and blank lines")
  void skipsHeaderAndBlanks() {
    assertThat(preview("user_guid,role\n\n" + GUID + ",FSPTS_VIEW_ALL\n\n")).hasSize(1);
  }

  @Test
  @DisplayName("reports the line number the error is on")
  void reportsLineNumbers() {
    String csv = "user_guid,role\n" + GUID + ",FSPTS_VIEW_ALL\n" + GUID + ",NOT_A_ROLE";

    assertThat(preview(csv))
        .filteredOn(row -> !row.valid())
        .singleElement()
        // A file of 200 rows needs to say which one, in the file's own numbering.
        .satisfies(row -> assertThat(row.lineNumber()).isEqualTo(3));
  }

  @Test
  @DisplayName("refuses the same pair twice rather than granting once and reporting twice")
  void refusesDuplicates() {
    String csv = GUID + ",FSPTS_VIEW_ALL\n" + GUID + ",fspts_view_all";

    assertThat(preview(csv))
        .filteredOn(row -> !row.valid())
        .singleElement()
        .satisfies(row -> assertThat(row.error()).contains("Duplicate"));
  }

  @Test
  @DisplayName("rejects an empty file rather than reporting nothing to do")
  void rejectsEmptyFile() {
    assertThatThrownBy(() -> service.preview(INTEGRATION, ENV, "user_guid,role\n", UPLOADER))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("no rows");
  }

  @Test
  @DisplayName("refuses a file above the row cap instead of granting part of it")
  void refusesOversizedFile() {
    String csv = (GUID + ",FSPTS_VIEW_ALL\n").repeat(201);

    assertThatThrownBy(() -> service.preview(INTEGRATION, ENV, csv, UPLOADER))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("most that can be uploaded");
  }

  // ---------------------------------------------------------------------------
  // Applying
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("preview writes nothing")
  void previewGrantsNothing() {
    preview(GUID + ",FSPTS_VIEW_ALL");

    verify(cssIntegrationService, never())
        .assignUserRoles(anyInt(), anyString(), any(), any());
  }

  @Test
  @DisplayName("grants a valid row, unscoped")
  void grantsValidRows() {
    when(cssIntegrationService.assignUserRoles(anyInt(), anyString(), any(), any()))
        .thenReturn(List.of(new CssUserRoleAssignmentResult(
            "FSPTS_VIEW_ALL", false, true, null, EmailSendingStatus.NOT_REQUIRED)));

    assertThat(service.apply(INTEGRATION, ENV, GUID + ",FSPTS_VIEW_ALL", UPLOADER))
        .singleElement()
        .satisfies(row -> assertThat(row.valid()).isTrue());

    org.mockito.ArgumentCaptor<CssUserRoleAssignmentRequest> captor =
        org.mockito.ArgumentCaptor.forClass(CssUserRoleAssignmentRequest.class);
    verify(cssIntegrationService)
        .assignUserRoles(anyInt(), anyString(), captor.capture(), any());

    assertThat(captor.getValue().userGuid()).isEqualTo(GUID);
    assertThat(captor.getValue().userType()).isEqualTo(UserType.IDIR);
    assertThat(captor.getValue().scopes()).isEmpty();
  }

  @Test
  @DisplayName("one failing row does not stop the others")
  void oneFailureDoesNotStopTheRest() {
    String other = "BBBBCCCCDDDDEEEEFFFF000011112222";
    when(userLookupClient.getIdirDetailByGuid(other)).thenReturn(Optional.of(
        new UserLookupIdirUserDto(true, "BOBJ", other, "Bob", "Jones", "bob@gov.bc.ca")));

    when(cssIntegrationService.assignUserRoles(anyInt(), anyString(), any(), any()))
        .thenThrow(new RuntimeException("CSS is down"))
        .thenReturn(List.of(new CssUserRoleAssignmentResult(
            "FSPTS_VIEW_ALL", false, true, null, EmailSendingStatus.NOT_REQUIRED)));

    // Unrelated people: the second must still be granted, and the first must say
    // why it was not.
    assertThat(service.apply(INTEGRATION, ENV,
        GUID + ",FSPTS_VIEW_ALL\n" + other + ",FSPTS_VIEW_ALL", UPLOADER))
        .satisfiesExactly(
            first -> {
              assertThat(first.valid()).isFalse();
              assertThat(first.error()).contains("CSS is down");
            },
            second -> assertThat(second.valid()).isTrue());
  }

  @Test
  @DisplayName("an invalid row is never attempted")
  void invalidRowsAreNotAttempted() {
    service.apply(INTEGRATION, ENV, GUID + ",NOT_A_ROLE", UPLOADER);

    verify(cssIntegrationService, never())
        .assignUserRoles(anyInt(), anyString(), any(), any());
  }

  @Test
  @DisplayName("applying re-validates rather than trusting a previewed payload")
  void applyRevalidates() {
    service.apply(INTEGRATION, ENV, GUID + ",FSPTS_VIEW_ALL", UPLOADER);

    // The roles and the directory are read again on apply; a payload edited
    // between the two steps cannot smuggle a row past the checks.
    verify(cssIntegrationService, org.mockito.Mockito.atLeastOnce())
        .getRoles(INTEGRATION, ENV);
    verify(userLookupClient, org.mockito.Mockito.atLeastOnce()).getIdirDetailByGuid(GUID);
  }
}
