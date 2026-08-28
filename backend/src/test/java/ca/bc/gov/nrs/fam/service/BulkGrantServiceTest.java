package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.EmailSendingStatus;
import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.dto.CssScopeSelection;
import ca.bc.gov.nrs.fam.constants.District;
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
import java.util.Map;
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

  /** The configured cap, small here so the oversized-file test stays quick. */
  private static final int MAX_ROWS = 200;
  /** What the file writes: the name a person is known by. */
  private static final String USERNAME = "JANES";

  /** What the directory answers with, and what CSS is then provisioned against. */
  private static final String GUID = "AABBCCDDEEFF00112233445566778899";

  @Mock private CssIntegrationService cssIntegrationService;
  @Mock private UserLookupClient userLookupClient;
  @Mock private AuthorizationService authorizationService;
  @Mock private ca.bc.gov.nrs.fam.security.TargetOrganizationGuard targetOrganizationGuard;
  @Mock private ca.bc.gov.nrs.fam.integration.ForestClientIntegrationService
      forestClientIntegrationService;
  @Mock private ApiInstanceEnvResolver apiInstanceEnvResolver;

  private BulkGrantService service;

  private static final Requester UPLOADER = Requester.builder()
      .userName("JSMITH").userGuid("EEEE1111").userType(UserType.IDIR)
      .accessRoles(List.of(FamAdminRole.appAdmin(INTEGRATION, ENV)))
      .build();

  private static CssRoleOptionDto role(String name, boolean district, boolean client) {
    return role(name, district, false, client);
  }

  private static CssRoleOptionDto role(
      String name, boolean district, boolean region, boolean client) {
    // Null grantable lists: the bulk uploader is an application administrator,
    // narrowed by nothing.
    return new CssRoleOptionDto(name, "View All", "Long description", null,
        false, List.of(), district, region, client, null, null, null);
  }

  @BeforeEach
  void setUp() {
    service = new BulkGrantService(
        cssIntegrationService, userLookupClient, authorizationService, targetOrganizationGuard,
        forestClientIntegrationService, apiInstanceEnvResolver, MAX_ROWS);

    // Districts come from a compile-time enum, so only the organisations need
    // an upstream. Default to "nothing found" - the tests that use one say so.
    when(apiInstanceEnvResolver.resolve(anyString())).thenReturn(ApiInstanceEnv.TEST);
    when(forestClientIntegrationService.search(anyList(), anyInt(), any(), anyBoolean()))
        .thenReturn(List.of());

    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("FSPTS_VIEW_ALL", false, false)));

    when(userLookupClient.getIdirDetail(USERNAME)).thenReturn(Optional.of(
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
    assertThat(preview(USERNAME + ",IDIR,FSPTS_VIEW_ALL"))
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
  @DisplayName("falls back to Business BCeID when the username is not an IDIR one")
  void resolvesBceid() {
    when(userLookupClient.getIdirDetail(USERNAME)).thenReturn(Optional.empty());
    when(userLookupClient.getBusinessBceid(UserLookupClient.SearchBy.USER_ID, USERNAME))
        .thenReturn(Optional.of(new UserLookupBceidUserDto(true,
            "JSMITH-BCEID", GUID, "BUSGUID", "Acme Forestry", "Jane", "Smith", "j@acme.com")));

    // The column is empty, so which directory the person is in has to be
    // discovered - and shown, so the uploader can see it was the right person.
    assertThat(preview(USERNAME + ",,FSPTS_VIEW_ALL"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.userType()).isEqualTo(UserType.BCEID);
          assertThat(row.organization()).isEqualTo("Acme Forestry");
        });
  }

  @Test
  @DisplayName("a username neither directory knows is an error, not a silent skip")
  void unknownUsername() {
    when(userLookupClient.getIdirDetail(anyString())).thenReturn(Optional.empty());
    when(userLookupClient.getBusinessBceid(any(), anyString())).thenReturn(Optional.empty());

    assertThat(preview(USERNAME + ",,FSPTS_VIEW_ALL"))
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
    assertThat(preview(USERNAME + ",IDIR,NOT_A_ROLE"))
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

      assertThat(preview(USERNAME + ",IDIR," + adminRole))
          .singleElement()
          .satisfies(row -> {
            assertThat(row.valid()).isFalse();
            assertThat(row.error()).contains("administrative role");
          });
    }
  }

  // ---------------------------------------------------------------------------
  // User type - the second column
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("searches only the directory the file named")
  void searchesOnlyTheStatedDirectory() {
    when(userLookupClient.getBusinessBceid(UserLookupClient.SearchBy.USER_ID, USERNAME))
        .thenReturn(Optional.of(new UserLookupBceidUserDto(true,
            "JSMITH-BCEID", GUID, "BUSGUID", "Acme Forestry", "Jane", "Smith", "j@acme.com")));

    preview(USERNAME + ",BCEID,FSPTS_VIEW_ALL");

    // Stating it is what the column buys: without it every Business BCeID row
    // pays for a failed IDIR lookup first.
    verify(userLookupClient, never()).getIdirDetail(anyString());
  }

  @Test
  @DisplayName("refuses a user who is not in the directory the file named")
  void refusesAUserFromTheOtherDirectory() {
    // The name is a real Business BCeID user, but the row says IDIR. Falling
    // back would grant a different person who happens to share the name.
    when(userLookupClient.getIdirDetail(USERNAME)).thenReturn(Optional.empty());
    when(userLookupClient.getBusinessBceid(UserLookupClient.SearchBy.USER_ID, USERNAME))
        .thenReturn(Optional.of(new UserLookupBceidUserDto(true,
            "JSMITH-BCEID", GUID, "BUSGUID", "Acme Forestry", "Jane", "Smith", "j@acme.com")));

    assertThat(preview(USERNAME + ",IDIR,FSPTS_VIEW_ALL"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isFalse();
          assertThat(row.error()).contains("No IDIR user");
        });
  }

  @Test
  @DisplayName("accepts BCEID as well as the stored BCEID_BUS")
  void acceptsEitherBceidSpelling() {
    when(userLookupClient.getBusinessBceid(UserLookupClient.SearchBy.USER_ID, USERNAME))
        .thenReturn(Optional.of(new UserLookupBceidUserDto(true,
            "JSMITH-BCEID", GUID, "BUSGUID", "Acme Forestry", "Jane", "Smith", "j@acme.com")));

    // The column is written by hand, and FAM admits only the business flavour
    // anyway, so demanding the longer form would reject files for no reason a
    // person could see.
    for (String spelling : List.of("BCEID", "BCEID_BUS", "bceid")) {
      assertThat(preview(USERNAME + "," + spelling + ",FSPTS_VIEW_ALL"))
          .singleElement()
          .satisfies(row -> assertThat(row.userType()).isEqualTo(UserType.BCEID));
    }
  }

  @Test
  @DisplayName("refuses a user type that is neither")
  void refusesAnUnknownUserType() {
    assertThat(preview(USERNAME + ",BCSC,FSPTS_VIEW_ALL"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isFalse();
          assertThat(row.error()).contains("not a user type");
        });
  }

  @Test
  @DisplayName("an empty user type still searches both directories")
  void emptyUserTypeSearchesBoth() {
    // The column is optional. A file written without it behaves as it always
    // did rather than failing on a value nobody was asked for.
    assertThat(preview(USERNAME + ",,FSPTS_VIEW_ALL"))
        .singleElement()
        .satisfies(row -> assertThat(row.valid()).isTrue());
  }

  // ---------------------------------------------------------------------------
  // Scope - the third and fourth columns
  // ---------------------------------------------------------------------------

  /** Makes the upstream answer for one organisation. */
  private void givenClient(String number, String name, String status) {
    when(forestClientIntegrationService.search(anyList(), anyInt(), any(), anyBoolean()))
        .thenReturn(List.of(Map.of(
            "clientNumber", number, "clientName", name, "clientStatusCode", status)));
  }

  @Test
  @DisplayName("grants a district-scoped role for the district named in the file")
  void acceptsADistrict() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_EDITOR", true, false)));

    assertThat(preview(USERNAME + ",IDIR,CHR_FREP_EDITOR,DCC"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isTrue();
          assertThat(row.district()).isEqualTo("DCC");
          // Resolved so the confirmation reads as a place rather than a code.
          assertThat(row.districtName()).contains("Cariboo-Chilcotin");
        });
  }

  @Test
  @DisplayName("refuses a district on a role that is not granted per district")
  void refusesADistrictOnAnUnscopedRole() {
    // The more dangerous direction: the value would simply be ignored, and the
    // row would grant wider access than the file appears to ask for.
    assertThat(preview(USERNAME + ",IDIR,FSPTS_VIEW_ALL,DCC"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isFalse();
          assertThat(row.error()).contains("not granted per district");
          // The offending value is still shown, or the error names nothing.
          assertThat(row.district()).isEqualTo("DCC");
        });
  }

  @Test
  @DisplayName("grants a region-scoped role from the sixth column")
  void grantsARegionScopedRole() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("FREP_REGIONAL", false, true, false)));

    // Region is column six, after organization - appended rather than slotted
    // in beside district, because the parser is positional and a file written
    // before regions existed must keep meaning what it did.
    assertThat(preview(USERNAME + ",IDIR,FREP_REGIONAL,,,CARIBOO"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isTrue();
          assertThat(row.region()).isEqualTo("CARIBOO");
          assertThat(row.regionName()).isEqualTo("Cariboo");
        });
  }

  @Test
  @DisplayName("a file written before regions existed still means what it did")
  void olderFilesAreUnchanged() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_EDITOR", true, false)));

    // Five columns, no region. The organization column must still be read as
    // one - appending the new column is what guarantees that.
    assertThat(preview(USERNAME + ",IDIR,CHR_FREP_EDITOR,DCC,"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isTrue();
          assertThat(row.district()).isEqualTo("DCC");
          assertThat(row.region()).isNull();
        });
  }

  @Test
  @DisplayName("refuses a region on a role that is not granted per region")
  void refusesARegionOnAnUnscopedRole() {
    assertThat(preview(USERNAME + ",IDIR,FSPTS_VIEW_ALL,,,CARIBOO"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isFalse();
          assertThat(row.error()).contains("not granted per region");
          assertThat(row.region()).isEqualTo("CARIBOO");
        });
  }

  @Test
  @DisplayName("refuses a region-scoped role with its region column left empty")
  void refusesARegionScopedRoleWithNoRegion() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("FREP_REGIONAL", false, true, false)));

    assertThat(preview(USERNAME + ",IDIR,FREP_REGIONAL"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isFalse();
          assertThat(row.error()).contains("granted per region");
        });
  }

  @Test
  @DisplayName("refuses a region code that is not one")
  void refusesAnUnknownRegion() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("FREP_REGIONAL", false, true, false)));

    assertThat(preview(USERNAME + ",IDIR,FREP_REGIONAL,,,NOPE"))
        .singleElement()
        .satisfies(row -> assertThat(row.error()).contains("not a natural resource region"));
  }

  @Test
  @DisplayName("tells two rows apart when only the region differs")
  void regionDistinguishesRows() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("FREP_REGIONAL", false, true, false)));

    // Same person, same role, two regions is two grants - not a duplicate. The
    // duplicate key has to include the region or the second reads as a repeat
    // and is silently dropped.
    assertThat(preview(USERNAME + ",IDIR,FREP_REGIONAL,,,CARIBOO\n"
            + USERNAME + ",IDIR,FREP_REGIONAL,,,SKEENA"))
        .hasSize(2)
        .allSatisfy(row -> assertThat(row.valid()).isTrue());
  }

  @Test
  @DisplayName("refuses an organization on a role that is not granted per organization")
  void refusesAClientOnAnUnscopedRole() {
    givenClient("00001012", "ACME LTD.", "ACT");

    assertThat(preview(USERNAME + ",IDIR,FSPTS_VIEW_ALL,,00001012"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isFalse();
          assertThat(row.error()).contains("not granted per organization");
        });
  }

  @Test
  @DisplayName("refuses a scoped role with its scope column left empty")
  void refusesAScopedRoleWithNoScope() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_EDITOR", true, false)));

    // Granting the base role instead would assign something no application
    // authorises on: a scoped grant only ever assigns per-scope roles.
    assertThat(preview(USERNAME + ",IDIR,CHR_FREP_EDITOR"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isFalse();
          assertThat(row.error()).contains("granted per district");
        });
  }

  @Test
  @DisplayName("refuses a district code that is not one")
  void refusesAnUnknownDistrict() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_EDITOR", true, false)));

    assertThat(preview(USERNAME + ",IDIR,CHR_FREP_EDITOR,NOPE"))
        .singleElement()
        .satisfies(row -> assertThat(row.error()).contains("not a natural resource district"));
  }

  @Test
  @DisplayName("refuses an expired district, as the picker does")
  void refusesAnExpiredDistrict() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_EDITOR", true, false)));

    String expired = java.util.Arrays.stream(District.values())
        .filter(District::isExpired).findFirst()
        .map(District::getOrgUnitCode).orElse(null);
    org.junit.jupiter.api.Assumptions.assumeTrue(
        expired != null, "no expired district in the enum to test with");

    // Expired districts are kept out of the picker so none can be granted; the
    // file must not be a way around that.
    assertThat(preview(USERNAME + ",IDIR,CHR_FREP_EDITOR," + expired))
        .singleElement()
        .satisfies(row -> assertThat(row.error()).contains("expired"));
  }

  @Test
  @DisplayName("resolves the organization's name for the confirmation")
  void resolvesTheOrganization() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_VIEWER", false, true)));
    givenClient("00001012", "ACME LTD.", "ACT");

    assertThat(preview(USERNAME + ",IDIR,CHR_FREP_VIEWER,,00001012"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isTrue();
          assertThat(row.forestClientNumber()).isEqualTo("00001012");
          assertThat(row.forestClientName()).isEqualTo("ACME LTD.");
        });
  }

  @Test
  @DisplayName("pads a client number a spreadsheet stripped the zeros from")
  void padsTheClientNumber() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_VIEWER", false, true)));
    givenClient("00001012", "ACME LTD.", "ACT");

    // Excel turns 00001012 into 1012. Without padding, every client-scoped row
    // of a file that has been through a spreadsheet is rejected as unknown.
    assertThat(preview(USERNAME + ",IDIR,CHR_FREP_VIEWER,,1012"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isTrue();
          assertThat(row.forestClientNumber()).isEqualTo("00001012");
        });
  }

  @Test
  @DisplayName("refuses an organization the Forest Client API does not know")
  void refusesAnUnknownOrganization() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_VIEWER", false, true)));

    assertThat(preview(USERNAME + ",IDIR,CHR_FREP_VIEWER,,00009999"))
        .singleElement()
        .satisfies(row -> assertThat(row.error()).contains("No organization"));
  }

  @Test
  @DisplayName("refuses an inactive organization, as the picker does")
  void refusesAnInactiveOrganization() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_VIEWER", false, true)));
    givenClient("00001012", "ACME LTD.", "DAC");

    // Findable but refused on selection, so granting one by upload would be a
    // dead end nobody could act on.
    assertThat(preview(USERNAME + ",IDIR,CHR_FREP_VIEWER,,00001012"))
        .singleElement()
        .satisfies(row -> assertThat(row.error()).contains("not active"));
  }

  @Test
  @DisplayName("a role scoped both ways needs both columns")
  void compoundRoleNeedsBoth() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_BOTH", true, true)));
    givenClient("00001012", "ACME LTD.", "ACT");

    assertThat(preview(USERNAME + ",IDIR,CHR_FREP_BOTH,DCC"))
        .singleElement()
        .satisfies(row -> assertThat(row.error()).contains("granted per organization"));

    assertThat(preview(USERNAME + ",IDIR,CHR_FREP_BOTH,DCC,00001012"))
        .singleElement()
        .satisfies(row -> assertThat(row.valid()).isTrue());
  }

  @Test
  @DisplayName("the same role for two districts is two rows, not a duplicate")
  void twoDistrictsAreNotADuplicate() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_EDITOR", true, false)));

    // The duplicate key includes the scope. Without that, the second district
    // would be reported as a repeat of the first and silently dropped.
    assertThat(preview(USERNAME + ",IDIR,CHR_FREP_EDITOR,DCC\n" + USERNAME + ",IDIR,CHR_FREP_EDITOR,DKA"))
        .allSatisfy(row -> assertThat(row.valid()).isTrue())
        .hasSize(2);
  }

  @Test
  @DisplayName("the same role for the same district twice is a duplicate")
  void sameDistrictTwiceIsADuplicate() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_EDITOR", true, false)));

    assertThat(preview(USERNAME + ",IDIR,CHR_FREP_EDITOR,DCC\n" + USERNAME + ",IDIR,CHR_FREP_EDITOR,DCC"))
        .element(1)
        .satisfies(row -> assertThat(row.error()).contains("Duplicate"));
  }

  @Test
  @DisplayName("resolves every organization in one upstream call, not one per row")
  void resolvesOrganizationsInOneCall() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_VIEWER", false, true)));
    givenClient("00001012", "ACME LTD.", "ACT");

    preview(USERNAME + ",IDIR,CHR_FREP_VIEWER,,00001012\n"
        + USERNAME + ",IDIR,CHR_FREP_VIEWER,,00001013\n"
        + USERNAME + ",IDIR,CHR_FREP_VIEWER,,00001014");

    // A two-hundred row file would otherwise spend two hundred round trips
    // confirming names nobody disputed.
    verify(forestClientIntegrationService, times(1))
        .search(anyList(), anyInt(), any(), anyBoolean());
  }

  @Test
  @DisplayName("grants the scope the row named, not the bare role")
  void grantsWithTheScope() {
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_EDITOR", true, false)));
    when(cssIntegrationService.assignUserRoles(anyInt(), anyString(), any(), any()))
        .thenReturn(List.of(new CssUserRoleAssignmentResult(
            "CHR_FREP_EDITOR_DISTRICT-DCC", false, true, null, null)));

    service.apply(INTEGRATION, ENV, USERNAME + ",IDIR,CHR_FREP_EDITOR,DCC", UPLOADER);

    // Sending no scope would assign the base role - something no application
    // authorises on - while reporting success.
    org.mockito.ArgumentCaptor<CssUserRoleAssignmentRequest> captor =
        org.mockito.ArgumentCaptor.forClass(CssUserRoleAssignmentRequest.class);
    verify(cssIntegrationService)
        .assignUserRoles(anyInt(), anyString(), captor.capture(), any());
    assertThat(captor.getValue().scopes())
        .containsExactly(new CssScopeSelection("DISTRICT", List.of("DCC")));
  }

  @Test
  @DisplayName("applies the same self-grant rule as the single grant path")
  void refusesSelfGrant() {
    org.mockito.Mockito.doThrow(FamHttpException.forbidden("self_grant_prohibited",
            "You cannot change your own permissions. Ask another administrator to do it for you."))
        .when(authorizationService).forbidSelfGrant(any(), anyString());

    // The row carries the reason, not just a refusal: it is the one line the
    // uploader reads to learn why that person was skipped.
    assertThat(preview(USERNAME + ",IDIR,FSPTS_VIEW_ALL"))
        .singleElement()
        .satisfies(row -> assertThat(row.error()).contains("your own permissions"));
  }

  @Test
  @DisplayName("applies the delegated administrator's role restriction")
  void refusesUndelegatedRole() {
    org.mockito.Mockito.doThrow(FamHttpException.forbidden("permission_required",
            "You have not been delegated FSPTS_VIEW_ALL in this application."))
        .when(authorizationService).requireGrantableRoles(any(), anyInt(), anyString(), any());

    assertThat(preview(USERNAME + ",IDIR,FSPTS_VIEW_ALL"))
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
    // it. The mark has to sit directly on a username for this to mean anything.
    assertThat(preview("\uFEFF" + USERNAME + ",IDIR,FSPTS_VIEW_ALL"))
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
    assertThat(preview("username,user_type,role\n" + USERNAME + ",IDIR,FSPTS_VIEW_ALL"))
        .singleElement()
        .satisfies(row -> assertThat(row.valid()).isTrue());
  }

  @Test
  @DisplayName("skips a header row and blank lines")
  void skipsHeaderAndBlanks() {
    assertThat(preview("username,user_type,role\n\n" + USERNAME + ",IDIR,FSPTS_VIEW_ALL\n\n")).hasSize(1);
  }

  @Test
  @DisplayName("reports the line number the error is on")
  void reportsLineNumbers() {
    String csv = "username,user_type,role\n" + USERNAME + ",IDIR,FSPTS_VIEW_ALL\n" + USERNAME + ",IDIR,NOT_A_ROLE";

    assertThat(preview(csv))
        .filteredOn(row -> !row.valid())
        .singleElement()
        // A file of 200 rows needs to say which one, in the file's own numbering.
        .satisfies(row -> assertThat(row.lineNumber()).isEqualTo(3));
  }

  @Test
  @DisplayName("refuses the same pair twice rather than granting once and reporting twice")
  void refusesDuplicates() {
    String csv = USERNAME + ",IDIR,FSPTS_VIEW_ALL\n" + USERNAME + ",IDIR,fspts_view_all";

    assertThat(preview(csv))
        .filteredOn(row -> !row.valid())
        .singleElement()
        .satisfies(row -> assertThat(row.error()).contains("Duplicate"));
  }

  @Test
  @DisplayName("rejects an empty file rather than reporting nothing to do")
  void rejectsEmptyFile() {
    assertThatThrownBy(() -> service.preview(INTEGRATION, ENV, "username,user_type,role\n", UPLOADER))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("no rows");
  }

  @Test
  @DisplayName("refuses a file above the row cap instead of granting part of it")
  void refusesOversizedFile() {
    String csv = (USERNAME + ",IDIR,FSPTS_VIEW_ALL\n").repeat(201);

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
    preview(USERNAME + ",IDIR,FSPTS_VIEW_ALL");

    verify(cssIntegrationService, never())
        .assignUserRoles(anyInt(), anyString(), any(), any());
  }

  @Test
  @DisplayName("grants a valid row, unscoped")
  void grantsValidRows() {
    when(cssIntegrationService.assignUserRoles(anyInt(), anyString(), any(), any()))
        .thenReturn(List.of(new CssUserRoleAssignmentResult(
            "FSPTS_VIEW_ALL", false, true, null, EmailSendingStatus.NOT_REQUIRED)));

    assertThat(service.apply(INTEGRATION, ENV, USERNAME + ",IDIR,FSPTS_VIEW_ALL", UPLOADER))
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
  @DisplayName("looks a person up once however many rows they appear on")
  void resolvesEachPersonOnce() {
    /*
        The reason a file may be large at all. A person granted a role for six
        districts is six rows and one human being; the directory is SOAP-backed
        and slow, and asking it the same question six times is what made a
        two-hundred row cap necessary in the first place.
    */
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_EDITOR", true, false)));

    String csv = USERNAME + ",IDIR,CHR_FREP_EDITOR,DCC\n"
        + USERNAME + ",IDIR,CHR_FREP_EDITOR,DKA\n"
        + USERNAME + ",IDIR,CHR_FREP_EDITOR,DMH\n";

    assertThat(preview(csv)).hasSize(3).allSatisfy(row ->
        assertThat(row.valid()).isTrue());

    verify(userLookupClient, org.mockito.Mockito.times(1)).getIdirDetail(USERNAME);
  }

  @Test
  @DisplayName("does not keep asking about a name nobody answers to")
  void remembersThatSomebodyWasNotFound() {
    // The absence has to be remembered too, or a file repeating one bad name
    // pays the full lookup on every one of its rows - which is the file most
    // likely to be large and wrong.
    when(userLookupClient.getIdirDetail(anyString())).thenReturn(Optional.empty());
    when(userLookupClient.getBusinessBceid(any(), anyString())).thenReturn(Optional.empty());

    String csv = ("NOBODY,IDIR,FSPTS_VIEW_ALL\n").repeat(5);

    assertThat(preview(csv)).hasSize(5).allSatisfy(row ->
        assertThat(row.valid()).isFalse());

    verify(userLookupClient, org.mockito.Mockito.times(1)).getIdirDetail("NOBODY");
  }

  @Test
  @DisplayName("flags a row the person already holds instead of granting it again")
  void skipsAGrantAlreadyMade() {
    /*
        Re-granting is harmless in CSS but it emails the person to say they have
        been given something they already had, and writes an audit row for a
        change that did not happen. A file re-uploaded after a partly failed run
        is mostly rows like this.
    */
    when(cssIntegrationService.rolesHeldBy(INTEGRATION, ENV, GUID, UserType.IDIR))
        .thenReturn(java.util.Set.of("FSPTS_VIEW_ALL"));

    assertThat(preview(USERNAME + ",IDIR,FSPTS_VIEW_ALL"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.alreadyGranted()).isTrue();
          assertThat(row.valid()).isFalse();
          // Nothing is wrong with it - it just needs nothing done.
          assertThat(row.error()).isNull();
          // And it still reads as a row: the person and the role resolved.
          assertThat(row.firstName()).isEqualTo("Jane");
          assertThat(row.roleDisplayName()).isEqualTo("View All");
        });
  }

  @Test
  @DisplayName("compares the scoped role, not the base one")
  void theSameRoleForAnotherDistrictIsStillAGrant() {
    // A base role held for one district says nothing about another. Comparing
    // base names would silently drop half of a multi-district file.
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_EDITOR", true, false)));
    when(cssIntegrationService.rolesHeldBy(INTEGRATION, ENV, GUID, UserType.IDIR))
        .thenReturn(java.util.Set.of("CHR_FREP_EDITOR_DISTRICT-DCC"));

    assertThat(preview(USERNAME + ",IDIR,CHR_FREP_EDITOR,DCC"))
        .singleElement()
        .satisfies(row -> assertThat(row.alreadyGranted()).isTrue());

    assertThat(preview(USERNAME + ",IDIR,CHR_FREP_EDITOR,DKA"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.alreadyGranted()).isFalse();
          assertThat(row.valid()).isTrue();
        });
  }

  @Test
  @DisplayName("does not apply a row it flagged as already granted")
  void appliesNothingForAnAlreadyGrantedRow() {
    when(cssIntegrationService.rolesHeldBy(INTEGRATION, ENV, GUID, UserType.IDIR))
        .thenReturn(java.util.Set.of("FSPTS_VIEW_ALL"));

    assertThat(service.apply(INTEGRATION, ENV, USERNAME + ",IDIR,FSPTS_VIEW_ALL", UPLOADER))
        .singleElement()
        .satisfies(row -> assertThat(row.alreadyGranted()).isTrue());

    verify(cssIntegrationService, org.mockito.Mockito.never())
        .assignUserRoles(anyInt(), anyString(), any(), any());
  }

  @Test
  @DisplayName("asks once per person what they already hold")
  void readsHeldRolesOncePerPerson() {
    // The same economy the directory lookup gets, for the same reason.
    when(cssIntegrationService.getRoles(INTEGRATION, ENV))
        .thenReturn(List.of(role("CHR_FREP_EDITOR", true, false)));

    preview(USERNAME + ",IDIR,CHR_FREP_EDITOR,DCC\n"
        + USERNAME + ",IDIR,CHR_FREP_EDITOR,DKA\n"
        + USERNAME + ",IDIR,CHR_FREP_EDITOR,DMH\n");

    verify(cssIntegrationService, org.mockito.Mockito.times(1))
        .rolesHeldBy(INTEGRATION, ENV, GUID, UserType.IDIR);
  }

  @Test
  @DisplayName("provisions CSS with the GUID the username resolved to")
  void provisionsWithTheResolvedGuid() {
    /*
        The whole point of naming people by username. The file carries no GUID,
        CSS accepts nothing else, and the directory lookup that names the person
        for the confirmation is what bridges the two.
    */
    when(cssIntegrationService.assignUserRoles(anyInt(), anyString(), any(), any()))
        .thenReturn(List.of(new CssUserRoleAssignmentResult(
            "FSPTS_VIEW_ALL", false, true, null, EmailSendingStatus.NOT_REQUIRED)));

    service.apply(INTEGRATION, ENV, USERNAME + ",IDIR,FSPTS_VIEW_ALL", UPLOADER);

    org.mockito.ArgumentCaptor<CssUserRoleAssignmentRequest> captor =
        org.mockito.ArgumentCaptor.forClass(CssUserRoleAssignmentRequest.class);
    verify(cssIntegrationService)
        .assignUserRoles(anyInt(), anyString(), captor.capture(), any());

    assertThat(captor.getValue().userGuid()).isEqualTo(GUID);
    assertThat(captor.getValue().userGuid()).isNotEqualTo(USERNAME);
  }

  @Test
  @DisplayName("refuses a user the directory can name but has no GUID for")
  void refusesAUserWithNoGuid() {
    // Nothing to provision against. Granting anyway would report success over a
    // grant made to nobody.
    when(userLookupClient.getIdirDetail(USERNAME)).thenReturn(Optional.of(
        new UserLookupIdirUserDto(true, USERNAME, null, "Jane", "Smith", "jane@gov.bc.ca")));

    assertThat(preview(USERNAME + ",IDIR,FSPTS_VIEW_ALL"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.valid()).isFalse();
          assertThat(row.error()).contains("no GUID");
          // Still named, so the line can be found in the spreadsheet.
          assertThat(row.userName()).isEqualTo(USERNAME);
        });
  }

  @Test
  @DisplayName("one failing row does not stop the others")
  void oneFailureDoesNotStopTheRest() {
    String other = "BOBJ";
    when(userLookupClient.getIdirDetail(other)).thenReturn(Optional.of(new UserLookupIdirUserDto(
        true, other, "BBBBCCCCDDDDEEEEFFFF000011112222", "Bob", "Jones", "bob@gov.bc.ca")));

    when(cssIntegrationService.assignUserRoles(anyInt(), anyString(), any(), any()))
        .thenThrow(new RuntimeException("CSS is down"))
        .thenReturn(List.of(new CssUserRoleAssignmentResult(
            "FSPTS_VIEW_ALL", false, true, null, EmailSendingStatus.NOT_REQUIRED)));

    // Unrelated people: the second must still be granted, and the first must say
    // why it was not.
    assertThat(service.apply(INTEGRATION, ENV,
        USERNAME + ",IDIR,FSPTS_VIEW_ALL\n" + other + ",IDIR,FSPTS_VIEW_ALL", UPLOADER))
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
    service.apply(INTEGRATION, ENV, USERNAME + ",IDIR,NOT_A_ROLE", UPLOADER);

    verify(cssIntegrationService, never())
        .assignUserRoles(anyInt(), anyString(), any(), any());
  }

  @Test
  @DisplayName("applying re-validates rather than trusting a previewed payload")
  void applyRevalidates() {
    service.apply(INTEGRATION, ENV, USERNAME + ",IDIR,FSPTS_VIEW_ALL", UPLOADER);

    // The roles and the directory are read again on apply; a payload edited
    // between the two steps cannot smuggle a row past the checks.
    verify(cssIntegrationService, org.mockito.Mockito.atLeastOnce())
        .getRoles(INTEGRATION, ENV);
    verify(userLookupClient, org.mockito.Mockito.atLeastOnce()).getIdirDetail(USERNAME);
  }
}
