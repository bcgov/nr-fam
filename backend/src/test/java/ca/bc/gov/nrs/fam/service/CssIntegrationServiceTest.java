package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.dto.CssIntegrationDto;
import ca.bc.gov.nrs.fam.dto.CssRoleCreateRequest;
import ca.bc.gov.nrs.fam.dto.CssRoleDto;
import ca.bc.gov.nrs.fam.dto.CssRoleOptionDto;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentRequest;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentResult;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRevokeRequest;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRowDto;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.CssApiService;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CssIntegrationService (port of router_css_integration.py)")
class CssIntegrationServiceTest {

  private static final int INTEGRATION = 6538;
  private static final int FAM_OWN_INTEGRATION = 22261;
  private static final String ENV = "dev";

  @Mock private CssApiService cssApiService;
  @Mock private PermissionAuditWriteService auditWriteService;
  @Mock private AccessGrantedEmailService accessGrantedEmailService;
  @Mock private AssignmentVisibilityService assignmentVisibilityService;
  @Mock private ca.bc.gov.nrs.fam.security.TargetOrganizationGuard targetOrganizationGuard;

  /**
   * Real config rather than a mock: the IDIR alias is the thing most likely to
   * be wrong, so the tests should exercise the actual resolution.
   */
  @org.mockito.Spy private FamProperties famProperties = new FamProperties(
      "dev", null,
      new FamProperties.Integration(null,
          new FamProperties.Integration.Css(null, null, null, null, FAM_OWN_INTEGRATION,
              new FamProperties.Integration.Css.IdpAliases(null, null), null),
          null, null),
      null);

  @org.mockito.Spy private AuthorizationService authorizationService =
      new AuthorizationService(famProperties);
  @InjectMocks private CssIntegrationService service;

  @org.junit.jupiter.api.BeforeEach
  void passThroughEmailStep() {
    // The notification reports its own outcome and never alters which roles were
    // assigned, so by default it hands the results straight back.
    when(accessGrantedEmailService.notifyGranted(any(), anyString(), any()))
        .thenAnswer(i -> i.getArgument(2));

    // Filtering and naming are tested on their own; here the rows come straight
    // back so these assertions see exactly what CSS reported.
    when(assignmentVisibilityService.visibleTo(any(), any()))
        .thenAnswer(i -> i.getArgument(1));
  }

  private static CssRoleDto role(String name, boolean composite) {
    return new CssRoleDto(name, composite);
  }

  private void givenRoles(CssRoleDto... roles) {
    when(cssApiService.getRoles(INTEGRATION, ENV)).thenReturn(List.of(roles));
  }

  private void givenComposites(String roleName, String... children) {
    when(cssApiService.getRoleComposites(INTEGRATION, ENV, roleName))
        .thenReturn(List.of(children));
  }

  // ---------------------------------------------------------------- applications

  @Test
  @DisplayName("fans an integration out into one application per environment")
  void fansIntegrationOutPerEnvironment() {
    // A CSS integration spans environments; a FAM application does not.
    when(cssApiService.getIntegrations()).thenReturn(List.of(
        new CssIntegrationDto(1, "FREP", null, List.of("dev", "test"), "applied", null, null)));

    assertThat(service.getApplications())
        .extracting(a -> a.integrationId() + "/" + a.environment() + "/" + a.description())
        .containsExactly("1/dev/FREP (DEV)", "1/test/FREP (TEST)");
  }

  @Test
  @DisplayName("skips an integration with no environments rather than failing")
  void skipsIntegrationWithNoEnvironments() {
    when(cssApiService.getIntegrations()).thenReturn(List.of(
        new CssIntegrationDto(1, "FREP", null, null, "applied", null, null)));

    assertThat(service.getApplications()).isEmpty();
  }

  // ----------------------------------------------------------------------- roles

  @Test
  @DisplayName("treats a role nothing else composes as selectable")
  void topOfChainIsSelectable() {
    // "Submitter (SLR)" is a row; the FREP_EDITOR it wraps is an implementation
    // detail of it, not a row of its own.
    givenRoles(role("Submitter (SLR)", true), role("FREP_EDITOR", false));
    givenComposites("Submitter (SLR)", "FREP_EDITOR");

    assertThat(service.getRoles(INTEGRATION, ENV))
        .extracting(CssRoleOptionDto::name)
        .containsExactly("Submitter (SLR)");
  }

  @Test
  @DisplayName("resolves scope type from a marker deeper than a direct child")
  void resolvesScopeFromGrandchildMarker() {
    // Submitter (CHR) -> CHR_FREP_EDITOR -> HAS_DISTRICT_ROLE
    givenRoles(
        role("Submitter (CHR)", true),
        role("CHR_FREP_EDITOR", true),
        role("HAS_DISTRICT_ROLE", false));
    givenComposites("Submitter (CHR)", "CHR_FREP_EDITOR");
    givenComposites("CHR_FREP_EDITOR", "HAS_DISTRICT_ROLE");

    assertThat(service.getRoles(INTEGRATION, ENV)).singleElement().satisfies(option -> {
      assertThat(option.name()).isEqualTo("Submitter (CHR)");
      assertThat(option.roleTypeDistrict()).isTrue();
      assertThat(option.roleTypeClient()).isFalse();
      // role_code is the first non-marker descendant.
      assertThat(option.roleCode()).isEqualTo("CHR_FREP_EDITOR");
      assertThat(option.composites()).containsExactly("CHR_FREP_EDITOR", "HAS_DISTRICT_ROLE");
    });
  }

  @Test
  @DisplayName("flags a forest-client scoped role")
  void resolvesForestClientScope() {
    givenRoles(role("Submitter", true), role("HAS_FOREST_CLIENT", false));
    givenComposites("Submitter", "HAS_FOREST_CLIENT");

    assertThat(service.getRoles(INTEGRATION, ENV)).singleElement().satisfies(option -> {
      assertThat(option.roleTypeClient()).isTrue();
      assertThat(option.roleTypeDistrict()).isFalse();
      // Only a marker below it, so there is no machine role code.
      assertThat(option.roleCode()).isNull();
    });
  }

  @Test
  @DisplayName("never offers a marker role as selectable")
  void markersAreNotSelectable() {
    givenRoles(role("HAS_DISTRICT_ROLE", false), role("HAS_FOREST_CLIENT", false));

    assertThat(service.getRoles(INTEGRATION, ENV)).isEmpty();
  }

  @Test
  @DisplayName("does not fetch children for a leaf role")
  void doesNotFetchChildrenForLeaves() {
    // GET /roles already reports the composite flag, so a leaf needs no call.
    givenRoles(role("FREP_ADMINISTRATOR", false));

    service.getRoles(INTEGRATION, ENV);

    verify(cssApiService, never()).getRoleComposites(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("survives a cyclic composite definition instead of overflowing the stack")
  void survivesCyclicDefinition() {
    // CSS does not prevent A -> B -> A.
    givenRoles(role("A", true), role("B", true));
    givenComposites("A", "B");
    givenComposites("B", "A");

    // Both are composed by something else, so neither is selectable - the point
    // is that resolving terminates at all.
    assertThat(service.getRoles(INTEGRATION, ENV)).isEmpty();
  }

  @Test
  @DisplayName("reads a role's description off its sidecar")
  void readsDescriptionFromSidecar() {
    // CSS holds a name and nothing else, so the description is a role of its own.
    givenRoles(
        role("FREP_ADMINISTRATOR", false),
        role("FAM:LABEL:FREP_ADMINISTRATOR:FREP Administrator", false));

    assertThat(service.getRoles(INTEGRATION, ENV)).singleElement().satisfies(option -> {
      assertThat(option.name()).isEqualTo("FREP_ADMINISTRATOR");
      assertThat(option.description()).isEqualTo("FREP Administrator");
    });
  }

  @Test
  @DisplayName("never offers a sidecar as a selectable role")
  void sidecarIsNotSelectable() {
    // It is metadata. Offering it would let somebody grant a description.
    givenRoles(role("FAM:LABEL:FREP_ADMINISTRATOR:FREP Administrator", false));

    assertThat(service.getRoles(INTEGRATION, ENV)).isEmpty();
  }

  @Test
  @DisplayName("leaves the description null for a role with no sidecar")
  void roleWithoutSidecarHasNoDescription() {
    // Roles predating this screen have none, and must still list.
    givenRoles(role("FREP_EDITOR", false));

    assertThat(service.getRoles(INTEGRATION, ENV))
        .singleElement()
        .satisfies(option -> assertThat(option.description()).isNull());
  }

  // -------------------------------------------------------------- role creation

  /** Whoever is defining the role; recorded on the audit row as the performer. */
  private static final Requester DEFINER = Requester.builder()
      .userName("JSMITH").userGuid("AAAA1111").accessRoles(java.util.List.of("FAM_ADMIN"))
      .build();

  private static CssRoleCreateRequest createRequest(
      String code, String description, boolean district, boolean client) {
    return new CssRoleCreateRequest(code, description, district, client);
  }

  @Test
  @DisplayName("creates the role under its code, plus a sidecar for the description")
  void createsRoleAndSidecar() {
    givenRoles();

    CssRoleOptionDto created = service.createRole(INTEGRATION, ENV,
        createRequest("FREP_ADMINISTRATOR", "FREP Administrator", false, false), DEFINER);

    verify(cssApiService).createRole(INTEGRATION, ENV, "FREP_ADMINISTRATOR");
    verify(cssApiService).createRole(
        INTEGRATION, ENV, "FAM:LABEL:FREP_ADMINISTRATOR:FREP Administrator");
    verify(cssApiService, never()).addRoleComposites(anyInt(), anyString(), anyString(), any());

    assertThat(created.name()).isEqualTo("FREP_ADMINISTRATOR");
    assertThat(created.description()).isEqualTo("FREP Administrator");
    assertThat(created.roleTypeDistrict()).isFalse();
    assertThat(created.roleTypeClient()).isFalse();
  }

  @Test
  @DisplayName("marks a district-scoped role by composing it from the marker")
  void composesDistrictMarker() {
    givenRoles();

    CssRoleOptionDto created = service.createRole(INTEGRATION, ENV,
        createRequest("CHR_FREP_EDITOR", "Submitter (CHR)", true, false), DEFINER);

    verify(cssApiService).addRoleComposites(
        INTEGRATION, ENV, "CHR_FREP_EDITOR", List.of("HAS_DISTRICT_ROLE"));
    assertThat(created.roleTypeDistrict()).isTrue();
  }

  @Test
  @DisplayName("creates the marker role when the integration has never used one")
  void createsMissingMarker() {
    // Composing from a role that does not exist would fail.
    givenRoles();

    service.createRole(INTEGRATION, ENV,
        createRequest("FOM_SUBMITTER", "Submitter", false, true), DEFINER);

    verify(cssApiService).createRole(INTEGRATION, ENV, "HAS_FOREST_CLIENT");
  }

  @Test
  @DisplayName("reuses a marker role that already exists")
  void reusesExistingMarker() {
    givenRoles(role("HAS_DISTRICT_ROLE", false));

    service.createRole(INTEGRATION, ENV, createRequest("R_ONE", "Role one", true, false), DEFINER);

    verify(cssApiService, never()).createRole(INTEGRATION, ENV, "HAS_DISTRICT_ROLE");
    verify(cssApiService).addRoleComposites(
        INTEGRATION, ENV, "R_ONE", List.of("HAS_DISTRICT_ROLE"));
  }

  @Test
  @DisplayName("upper cases the code rather than rejecting a lower case entry")
  void upperCasesCode() {
    givenRoles();

    assertThat(service.createRole(INTEGRATION, ENV,
        createRequest(" frep_administrator ", " FREP Administrator ", false, false), DEFINER).name())
        .isEqualTo("FREP_ADMINISTRATOR");
  }

  @Test
  @DisplayName("refuses a code that already exists rather than redefining it")
  void refusesDuplicateCode() {
    // People may already hold it; "create" must not silently change what they have.
    givenRoles(role("FREP_ADMINISTRATOR", false));

    assertThatThrownBy(() -> service.createRole(INTEGRATION, ENV,
        createRequest("FREP_ADMINISTRATOR", "FREP Administrator", false, false), DEFINER))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("already exists");

    verify(cssApiService, never()).createRole(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("refuses a code that is not a usable role name")
  void refusesInvalidCode() {
    givenRoles();

    assertThatThrownBy(() -> service.createRole(INTEGRATION, ENV,
        createRequest("Submitter (SLR)", "Submitter", false, false), DEFINER))
        .isInstanceOf(FamHttpException.class);

    verify(cssApiService, never()).createRole(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("refuses a code that would collide with a scope marker")
  void refusesMarkerCode() {
    // Creating HAS_DISTRICT_ROLE as a role would make every scoped role in the
    // integration point at it.
    givenRoles();

    assertThatThrownBy(() -> service.createRole(INTEGRATION, ENV,
        createRequest("HAS_DISTRICT_ROLE", "Anything", false, false), DEFINER))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("reserved");
  }

  @Test
  @DisplayName("refuses a role scoped by district and forest client at once")
  void refusesBothScopes() {
    // A grant carries one scope type, so one of the two would be unreachable.
    givenRoles();

    assertThatThrownBy(() -> service.createRole(INTEGRATION, ENV,
        createRequest("R_ONE", "Role one", true, true), DEFINER))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("not both");

    verify(cssApiService, never()).createRole(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("removes the role it created when a later step fails")
  void rollsBackOnFailure() {
    // Half a role - one with no description - looks exactly like one somebody
    // meant to create, and cannot be finished from the screen because the code is
    // already taken.
    givenRoles();
    when(cssApiService.createRole(INTEGRATION, ENV, "R_ONE")).thenReturn(true);
    doThrow(new IllegalStateException("boom")).when(cssApiService)
        .createRole(INTEGRATION, ENV, "FAM:LABEL:R_ONE:Role one");

    assertThatThrownBy(() -> service.createRole(INTEGRATION, ENV,
        createRequest("R_ONE", "Role one", false, false), DEFINER))
        .isInstanceOf(IllegalStateException.class);

    verify(cssApiService).deleteRole(INTEGRATION, ENV, "R_ONE");
  }

  @Test
  @DisplayName("does not delete a marker it did not create when rolling back")
  void rollbackSparesPreexistingMarker() {
    // Other roles are composed from it; deleting it would silently unscope them.
    givenRoles(role("HAS_DISTRICT_ROLE", false));
    when(cssApiService.createRole(INTEGRATION, ENV, "R_ONE")).thenReturn(true);
    doThrow(new IllegalStateException("boom")).when(cssApiService)
        .addRoleComposites(anyInt(), anyString(), anyString(), any());

    assertThatThrownBy(() -> service.createRole(INTEGRATION, ENV,
        createRequest("R_ONE", "Role one", true, false), DEFINER))
        .isInstanceOf(IllegalStateException.class);

    verify(cssApiService).deleteRole(INTEGRATION, ENV, "R_ONE");
    verify(cssApiService, never()).deleteRole(INTEGRATION, ENV, "HAS_DISTRICT_ROLE");
  }

  @Test
  @DisplayName("surfaces the original failure even when the cleanup also fails")
  void cleanupFailureDoesNotMaskTheCause() {
    givenRoles();
    when(cssApiService.createRole(INTEGRATION, ENV, "R_ONE")).thenReturn(true);
    doThrow(new IllegalStateException("the real cause")).when(cssApiService)
        .createRole(INTEGRATION, ENV, "FAM:LABEL:R_ONE:Role one");
    doThrow(new IllegalStateException("cleanup failed too")).when(cssApiService)
        .deleteRole(anyInt(), anyString(), anyString());

    assertThatThrownBy(() -> service.createRole(INTEGRATION, ENV,
        createRequest("R_ONE", "Role one", false, false), DEFINER))
        .hasMessage("the real cause");
  }

  @Test
  @DisplayName("records who defined the role, and what they defined")
  void auditsRoleCreation() {
    // CSS keeps no history of role definitions, so this row is the only record
    // that the role was introduced at all.
    givenRoles();

    service.createRole(INTEGRATION, ENV,
        createRequest("FREP_ADMINISTRATOR", "FREP Administrator", true, false), DEFINER);

    verify(auditWriteService).storeRoleCreated(
        DEFINER, INTEGRATION, ENV, "FREP_ADMINISTRATOR", "FREP Administrator", "DISTRICT");
  }

  @Test
  @DisplayName("audits the normalised code, not what was typed")
  void auditsNormalisedCode() {
    // The trail has to name the role that exists, or it cannot be matched to it.
    givenRoles();

    service.createRole(INTEGRATION, ENV,
        createRequest(" frep_administrator ", " FREP Administrator ", false, false), DEFINER);

    verify(auditWriteService).storeRoleCreated(
        DEFINER, INTEGRATION, ENV, "FREP_ADMINISTRATOR", "FREP Administrator", null);
  }

  @Test
  @DisplayName("writes no audit record when the role was not created")
  void noAuditWhenCreationFails() {
    // The role does not exist, so a record saying it was defined would be false.
    givenRoles();
    doThrow(new IllegalStateException("boom")).when(cssApiService)
        .createRole(INTEGRATION, ENV, "R_ONE");

    assertThatThrownBy(() -> service.createRole(INTEGRATION, ENV,
        createRequest("R_ONE", "Role one", false, false), DEFINER))
        .isInstanceOf(IllegalStateException.class);

    verify(auditWriteService, never()).storeRoleCreated(
        any(), anyInt(), anyString(), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("writes no audit record when the code was rejected")
  void noAuditWhenCodeRejected() {
    givenRoles(role("R_ONE", false));

    assertThatThrownBy(() -> service.createRole(INTEGRATION, ENV,
        createRequest("R_ONE", "Role one", false, false), DEFINER))
        .isInstanceOf(FamHttpException.class);

    verify(auditWriteService, never()).storeRoleCreated(
        any(), anyInt(), anyString(), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("a created role is immediately selectable, with its description")
  void createdRoleIsSelectable() {
    // The point of the screen: the role has to show up on the grant form after.
    givenRoles();
    service.createRole(INTEGRATION, ENV,
        createRequest("FREP_ADMINISTRATOR", "FREP Administrator", true, false), DEFINER);

    // What CSS would report on the next read.
    givenRoles(
        role("FREP_ADMINISTRATOR", true),
        role("HAS_DISTRICT_ROLE", false),
        role("FAM:LABEL:FREP_ADMINISTRATOR:FREP Administrator", false));
    givenComposites("FREP_ADMINISTRATOR", "HAS_DISTRICT_ROLE");

    assertThat(service.getRoles(INTEGRATION, ENV)).singleElement().satisfies(option -> {
      assertThat(option.name()).isEqualTo("FREP_ADMINISTRATOR");
      assertThat(option.description()).isEqualTo("FREP Administrator");
      assertThat(option.roleTypeDistrict()).isTrue();
    });
  }

  // ------------------------------------------------------------------ assignment

  private CssUserRoleAssignmentRequest request(String scopeType, List<String> values) {
    return new CssUserRoleAssignmentRequest(
        "AABBCCDDEEFF00112233445566778899", UserType.IDIR, "CHR_FREP_EDITOR",
        "jane@gov.bc.ca", scopeType, values);
  }

  private static Requester requesterWithGuid(String guid) {
    return Requester.builder()
        .userName("JSMITH").userGuid(guid).accessRoles(List.of("X_ADMIN")).build();
  }

  @Test
  @DisplayName("refuses a grant the requester is making to themselves")
  void refusesSelfGrant() {
    // Nothing should reach CSS: the grant is rejected before any role is created
    // or assigned.
    Requester self = requesterWithGuid("AABBCCDDEEFF00112233445566778899");

    assertThatThrownBy(() -> service.assignUserRoles(
        INTEGRATION, ENV, request("DISTRICT", List.of("DCC")), self))
        .isInstanceOf(FamHttpException.class);

    verify(cssApiService, never()).createRole(anyInt(), anyString(), anyString());
    verify(cssApiService, never()).assignUserRoles(anyInt(), anyString(), anyString(), any());
    verify(auditWriteService, never()).storeCssGranted(
        any(), anyString(), anyString(), anyInt(), anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("matches self-grant regardless of GUID casing")
  void refusesSelfGrantRegardlessOfCasing() {
    // FAM stores GUIDs upper case; a lower-cased one is the same person.
    Requester self = requesterWithGuid("aabbccddeeff00112233445566778899");

    assertThatThrownBy(() -> service.assignUserRoles(
        INTEGRATION, ENV, request(null, List.of()), self))
        .isInstanceOf(FamHttpException.class);
  }

  @Test
  @DisplayName("refuses a grant the organisation guard rejects, before reaching CSS")
  void organizationGuardBlocksTheGrant() {
    // A Business BCeID administrator may only grant within their own
    // organisation. Nothing may reach CSS when the guard refuses - a role
    // created for a refused grant would outlive it.
    givenRoles();
    doThrow(FamHttpException.forbidden("permission_required", "different org"))
        .when(targetOrganizationGuard).requireSameOrganization(any(), any(), anyString());

    assertThatThrownBy(() -> service.assignUserRoles(
        INTEGRATION, ENV, request(null, List.of()), requesterWithGuid("SOMEONEELSE")))
        .isInstanceOf(FamHttpException.class);

    verify(cssApiService, never()).createRole(anyInt(), anyString(), anyString());
    verify(cssApiService, never()).assignUserRoles(anyInt(), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("checks the organisation of the user actually named in the request")
  void organizationGuardSeesTheRequestedTarget() {
    givenRoles();

    service.assignUserRoles(INTEGRATION, ENV, request(null, List.of()),
        requesterWithGuid("SOMEONEELSE"));

    verify(targetOrganizationGuard).requireSameOrganization(
        any(), eq(UserType.IDIR), eq("AABBCCDDEEFF00112233445566778899"));
  }

  @Test
  @DisplayName("allows a grant to somebody else")
  void allowsGrantToAnotherUser() {
    givenRoles(role("CHR_FREP_EDITOR", false));

    assertThat(service.assignUserRoles(
        INTEGRATION, ENV, request(null, List.of()), requesterWithGuid("SOMEONEELSE")))
        .singleElement()
        .satisfies(r -> assertThat(r.assigned()).isTrue());
  }

  @Test
  @DisplayName("a system grant with no requester is not treated as a self-grant")
  void systemGrantSkipsSelfGrantCheck() {
    // There is nobody to self-grant to on the system path.
    givenRoles(role("CHR_FREP_EDITOR", false));

    assertThat(service.assignUserRoles(INTEGRATION, ENV, request(null, List.of())))
        .singleElement()
        .satisfies(r -> assertThat(r.assigned()).isTrue());
  }

  @Test
  @DisplayName("assigns an unscoped role as-is")
  void assignsUnscopedRoleAsIs() {
    givenRoles(role("CHR_FREP_EDITOR", false));

    List<CssUserRoleAssignmentResult> results =
        service.assignUserRoles(INTEGRATION, ENV, request(null, List.of()));

    assertThat(results).singleElement().satisfies(r -> {
      assertThat(r.roleName()).isEqualTo("CHR_FREP_EDITOR");
      assertThat(r.roleCreated()).isFalse();
      assertThat(r.assigned()).isTrue();
    });
    verify(cssApiService, never()).createRole(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("creates one role per scope value and assigns those instead of the base role")
  void createsOneRolePerScopeValue() {
    givenRoles(role("CHR_FREP_EDITOR", false));

    List<CssUserRoleAssignmentResult> results =
        service.assignUserRoles(INTEGRATION, ENV, request("DISTRICT", List.of("DCC", "DQU")));

    assertThat(results).extracting(CssUserRoleAssignmentResult::roleName)
        .containsExactly("CHR_FREP_EDITOR_DISTRICT-DCC", "CHR_FREP_EDITOR_DISTRICT-DQU");
    assertThat(results).allSatisfy(r -> {
      assertThat(r.roleCreated()).isTrue();
      assertThat(r.assigned()).isTrue();
    });

    // The base role itself is not assigned - only the generated ones.
    ArgumentCaptor<List<String>> assigned = ArgumentCaptor.forClass(List.class);
    verify(cssApiService).assignUserRoles(eq(INTEGRATION), eq(ENV),
        eq("aabbccddeeff00112233445566778899@azureidir"), assigned.capture());
    assertThat(assigned.getValue()).doesNotContain("CHR_FREP_EDITOR");
  }

  @Test
  @DisplayName("does not recreate a scope role that already exists")
  void doesNotRecreateExistingScopeRole() {
    givenRoles(role("CHR_FREP_EDITOR", false), role("CHR_FREP_EDITOR_DISTRICT-DCC", false));

    List<CssUserRoleAssignmentResult> results =
        service.assignUserRoles(INTEGRATION, ENV, request("DISTRICT", List.of("DCC")));

    assertThat(results).singleElement().satisfies(r -> {
      assertThat(r.roleCreated()).isFalse();
      assertThat(r.assigned()).isTrue();
    });
    verify(cssApiService, never()).createRole(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("collapses duplicate scope values to one role")
  void collapsesDuplicateScopeValues() {
    // Otherwise the same role is created twice and reported twice.
    givenRoles(role("CHR_FREP_EDITOR", false));

    assertThat(service.assignUserRoles(
        INTEGRATION, ENV, request("DISTRICT", List.of("DCC", "DCC"))))
        .hasSize(1);
  }

  @Test
  @DisplayName("one failing scope role does not discard the others")
  void oneFailingRoleDoesNotDiscardOthers() {
    givenRoles(role("CHR_FREP_EDITOR", false));
    doThrow(new RuntimeException("CSS exploded"))
        .when(cssApiService).createRole(INTEGRATION, ENV, "CHR_FREP_EDITOR_DISTRICT-DQU");

    List<CssUserRoleAssignmentResult> results =
        service.assignUserRoles(INTEGRATION, ENV, request("DISTRICT", List.of("DCC", "DQU")));

    assertThat(results).hasSize(2);
    assertThat(results).filteredOn(r -> r.roleName().endsWith("DCC")).singleElement()
        .satisfies(r -> assertThat(r.assigned()).isTrue());
    assertThat(results).filteredOn(r -> r.roleName().endsWith("DQU")).singleElement()
        .satisfies(r -> {
          assertThat(r.assigned()).isFalse();
          assertThat(r.errorMessage()).contains("CSS exploded");
        });
  }

  @Test
  @DisplayName("reports every role as unassigned when the assignment call itself fails")
  void assignmentFailureMarksAllUnassigned() {
    givenRoles(role("CHR_FREP_EDITOR", false));
    doThrow(new RuntimeException("assign failed"))
        .when(cssApiService).assignUserRoles(anyInt(), anyString(), anyString(), any());

    List<CssUserRoleAssignmentResult> results =
        service.assignUserRoles(INTEGRATION, ENV, request("DISTRICT", List.of("DCC")));

    assertThat(results).singleElement().satisfies(r -> {
      // The role was still created; only the assignment failed.
      assertThat(r.roleCreated()).isTrue();
      assertThat(r.assigned()).isFalse();
      assertThat(r.errorMessage()).contains("assign failed");
    });
  }

  @Test
  @DisplayName("rejects scope values with no scope type as a bad request")
  void rejectsScopeValuesWithoutScopeType() {
    assertThatThrownBy(() ->
        service.assignUserRoles(INTEGRATION, ENV, request(null, List.of("DCC"))))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("scope_type");

    verify(cssApiService, never()).assignUserRoles(anyInt(), anyString(), anyString(), any());
  }

  // --------------------------------------------------------------------- revoke

  private static CssUserRoleRevokeRequest revokeRequest(
      String scopeType, String scopeValue) {
    return new CssUserRoleRevokeRequest(
        "AABBCCDDEEFF00112233445566778899", UserType.IDIR, "CHR_FREP_EDITOR",
        scopeType, scopeValue);
  }

  @Test
  @DisplayName("removes the scope-specific role, not the base role")
  void revokesTheScopedRole() {
    // The base role is not what the user holds; removing it would take away
    // nothing and report success.
    service.revokeUserRole(INTEGRATION, ENV, revokeRequest("DISTRICT", "DCC"), DEFINER);

    verify(cssApiService).removeUserRole(INTEGRATION, ENV,
        "aabbccddeeff00112233445566778899@azureidir", "CHR_FREP_EDITOR_DISTRICT-DCC");
  }

  @Test
  @DisplayName("removes an unscoped role by its own name")
  void revokesUnscopedRole() {
    service.revokeUserRole(INTEGRATION, ENV, revokeRequest(null, null), DEFINER);

    verify(cssApiService).removeUserRole(INTEGRATION, ENV,
        "aabbccddeeff00112233445566778899@azureidir", "CHR_FREP_EDITOR");
  }

  @Test
  @DisplayName("records the revocation, the only trace it happened")
  void auditsTheRevocation() {
    // CSS keeps no history of what it removed.
    service.revokeUserRole(INTEGRATION, ENV, revokeRequest("DISTRICT", "DCC"), DEFINER);

    verify(auditWriteService).storeCssRevoked(
        eq(DEFINER), eq("AABBCCDDEEFF00112233445566778899"), eq("I"),
        eq(INTEGRATION), eq(ENV), eq("CHR_FREP_EDITOR"), eq("DISTRICT"),
        eq(List.of("CHR_FREP_EDITOR_DISTRICT-DCC")));
  }

  @Test
  @DisplayName("refuses a requester revoking their own access")
  void refusesSelfRevoke() {
    // Removing access is not the safer direction, so it is not the looser one.
    Requester self = requesterWithGuid("AABBCCDDEEFF00112233445566778899");

    assertThatThrownBy(() ->
        service.revokeUserRole(INTEGRATION, ENV, revokeRequest(null, null), self))
        .isInstanceOf(FamHttpException.class);

    verify(cssApiService, never()).removeUserRole(anyInt(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("applies the organisation rule to a revocation too")
  void appliesOrganizationGuardToRevoke() {
    doThrow(FamHttpException.forbidden("permission_required", "different org"))
        .when(targetOrganizationGuard).requireSameOrganization(any(), any(), anyString());

    assertThatThrownBy(() ->
        service.revokeUserRole(INTEGRATION, ENV, revokeRequest(null, null), DEFINER))
        .isInstanceOf(FamHttpException.class);

    verify(cssApiService, never()).removeUserRole(anyInt(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("writes no audit record when the removal itself failed")
  void noAuditWhenRevokeFails() {
    // A record saying the role was taken away, when it was not, is worse than
    // no record.
    doThrow(new IllegalStateException("boom")).when(cssApiService)
        .removeUserRole(anyInt(), anyString(), anyString(), anyString());

    assertThatThrownBy(() ->
        service.revokeUserRole(INTEGRATION, ENV, revokeRequest(null, null), DEFINER))
        .isInstanceOf(IllegalStateException.class);

    verify(auditWriteService, never()).storeCssRevoked(
        any(), anyString(), anyString(), anyInt(), anyString(), anyString(), any(), any());
  }

  // ------------------------------------------------------------------- read back

  @Test
  @DisplayName("labels an assignment row with the role's description")
  void labelsAssignmentRowWithDescription() {
    // The sidecar is already in the role list this method fetches, so the
    // description costs nothing extra to resolve.
    givenRoles(
        role("FREP_ADMINISTRATOR", false),
        role("FAM:LABEL:FREP_ADMINISTRATOR:FREP Administrator", false));
    when(cssApiService.getUsersWithRole(INTEGRATION, ENV, "FREP_ADMINISTRATOR"))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "abc@azureidir", "Jane", "Smith", "jane@gov.bc.ca", null)));

    assertThat(service.getUserRoleAssignments(INTEGRATION, ENV, DEFINER))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.roleName()).isEqualTo("FREP_ADMINISTRATOR");
          assertThat(row.roleDescription()).isEqualTo("FREP Administrator");
        });
  }

  @Test
  @DisplayName("describes a scoped role by its base role's description")
  void describesScopedRoleByBaseRole() {
    // The scope-specific role is generated per district and has no sidecar of
    // its own; the description belongs to the role it was generated from.
    givenRoles(
        role("CHR_FREP_EDITOR_DISTRICT-DCC", false),
        role("FAM:LABEL:CHR_FREP_EDITOR:Submitter (CHR)", false));
    when(cssApiService.getUsersWithRole(INTEGRATION, ENV, "CHR_FREP_EDITOR_DISTRICT-DCC"))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "abc@azureidir", "Jane", "Smith", "jane@gov.bc.ca", null)));

    assertThat(service.getUserRoleAssignments(INTEGRATION, ENV, DEFINER))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.roleDescription()).isEqualTo("Submitter (CHR)");
          assertThat(row.scopeValue()).isEqualTo("DCC");
        });
  }

  @Test
  @DisplayName("leaves the description null for a role with no sidecar")
  void assignmentRowWithoutSidecarHasNoDescription() {
    // Roles predating the convention have none, and the caller falls back to
    // the code rather than showing nothing.
    givenRoles(role("FREP_EDITOR", false));
    when(cssApiService.getUsersWithRole(INTEGRATION, ENV, "FREP_EDITOR"))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "abc@azureidir", "Jane", "Smith", "jane@gov.bc.ca", null)));

    assertThat(service.getUserRoleAssignments(INTEGRATION, ENV, DEFINER))
        .singleElement()
        .satisfies(row -> assertThat(row.roleDescription()).isNull());
  }

  @Test
  @DisplayName("recovers scope from the role name when listing assignments")
  void recoversScopeFromRoleName() {
    givenRoles(role("CHR_FREP_EDITOR_DISTRICT-DCC", false));
    when(cssApiService.getUsersWithRole(INTEGRATION, ENV, "CHR_FREP_EDITOR_DISTRICT-DCC"))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "aabb@idir", "Jane", "Smith", "jane@gov.bc.ca", null)));

    assertThat(service.getUserRoleAssignments(INTEGRATION, ENV, DEFINER)).singleElement()
        .satisfies(row -> {
          assertThat(row.roleName()).isEqualTo("CHR_FREP_EDITOR");
          assertThat(row.scopeType()).isEqualTo("DISTRICT");
          assertThat(row.scopeValue()).isEqualTo("DCC");
          assertThat(row.domain()).isEqualTo("IDIR");
          assertThat(row.firstName()).isEqualTo("Jane");
        });
  }

  @Test
  @DisplayName("emits one row per user/role pair across every role")
  void emitsRowPerUserRolePair() {
    givenRoles(role("R1", false), role("R2", false));
    when(cssApiService.getUsersWithRole(INTEGRATION, ENV, "R1")).thenReturn(List.of(
        new CssApiService.CssUserDto("a@idir", null, null, null, null),
        new CssApiService.CssUserDto("b@bceidbusiness", null, null, null, null)));
    when(cssApiService.getUsersWithRole(INTEGRATION, ENV, "R2")).thenReturn(List.of(
        new CssApiService.CssUserDto("a@idir", null, null, null, null)));

    List<CssUserRoleRowDto> rows = service.getUserRoleAssignments(INTEGRATION, ENV, DEFINER);

    assertThat(rows).hasSize(3);
    assertThat(rows).extracting(CssUserRoleRowDto::domain)
        .containsExactly("IDIR", "BCEID", "IDIR");
  }

  @Test
  @DisplayName("leaves an unrecognised username domain null rather than guessing")
  void unknownDomainIsNull() {
    givenRoles(role("R1", false));
    when(cssApiService.getUsersWithRole(INTEGRATION, ENV, "R1")).thenReturn(List.of(
        new CssApiService.CssUserDto("someone@bceidbasic", null, null, null, null)));

    assertThat(service.getUserRoleAssignments(INTEGRATION, ENV, DEFINER)).singleElement()
        .satisfies(row -> assertThat(row.domain()).isNull());
  }
}
