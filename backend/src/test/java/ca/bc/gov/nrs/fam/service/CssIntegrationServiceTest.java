package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
import ca.bc.gov.nrs.fam.dto.ScopeDto;
import ca.bc.gov.nrs.fam.dto.CssScopeSelection;
import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.dto.CssAdministratorAppointRequest;
import ca.bc.gov.nrs.fam.dto.CssAdministratorRowDto;
import ca.bc.gov.nrs.fam.dto.CssApplicationOptionDto;
import ca.bc.gov.nrs.fam.dto.CssDelegatedAdminRequest;
import ca.bc.gov.nrs.fam.dto.CssIntegrationDto;
import ca.bc.gov.nrs.fam.dto.CssRoleCreateRequest;
import ca.bc.gov.nrs.fam.dto.CssRoleBulkCreateResultDto;
import ca.bc.gov.nrs.fam.dto.CssRoleDeleteResultDto;
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

  private static final int INTEGRATION = 54321;
  private static final int FAM_OWN_INTEGRATION = 12345;
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
          null, null));

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

  @Test
  @DisplayName("marks FAM's own integration, in every one of its environments")
  void marksOwnIntegration() {
    when(cssApiService.getIntegrations()).thenReturn(List.of(
        new CssIntegrationDto(FAM_OWN_INTEGRATION, "FAM", null,
            List.of("dev", "test", "prod"), "applied", null, null),
        new CssIntegrationDto(INTEGRATION, "FREP", null, List.of("dev"), "applied",
            null, null)));

    assertThat(service.getApplications())
        .filteredOn(CssApplicationOptionDto::famApplication)
        .extracting(CssApplicationOptionDto::environment)
        // All three, because dev/test/prod are one integration.
        .containsExactly("dev", "test", "prod");
  }

  @Test
  @DisplayName("an ordinary application is not marked as FAM")
  void ordinaryApplicationIsNotMarked() {
    when(cssApiService.getIntegrations()).thenReturn(List.of(
        new CssIntegrationDto(INTEGRATION, "FREP", null, List.of("dev"), "applied",
            null, null)));

    assertThat(service.getApplications())
        .singleElement()
        .satisfies(app -> assertThat(app.famApplication()).isFalse());
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
  @DisplayName("reads a role's name and description off its two sidecars")
  void readsSidecars() {
    // CSS holds a name and nothing else, so each text is a role of its own.
    givenRoles(
        role("FSPTS_VIEW_ALL", false),
        role("FAM:LABEL:FSPTS_VIEW_ALL:View All", false),
        role("FAM:DESC:FSPTS_VIEW_ALL:Allows users to view all the FSPs but not edit", false));

    assertThat(service.getRoles(INTEGRATION, ENV)).singleElement().satisfies(option -> {
      assertThat(option.name()).isEqualTo("FSPTS_VIEW_ALL");
      assertThat(option.displayName()).isEqualTo("View All");
      assertThat(option.description())
          .isEqualTo("Allows users to view all the FSPs but not edit");
    });
  }

  @Test
  @DisplayName("a role predating descriptions keeps its name and simply has none")
  void labelOnlyRoleStillReadsItsName() {
    // Every role defined before FAM:DESC existed has only a FAM:LABEL sidecar,
    // and its text has always been the short name.
    givenRoles(
        role("FREP_ADMINISTRATOR", false),
        role("FAM:LABEL:FREP_ADMINISTRATOR:FREP Administrator", false));

    assertThat(service.getRoles(INTEGRATION, ENV)).singleElement().satisfies(option -> {
      assertThat(option.displayName()).isEqualTo("FREP Administrator");
      assertThat(option.description()).isNull();
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

  /**
   * The second argument is the role's short NAME - what the fixtures have always
   * held ("FREP Administrator"). The long description is a separate field now and
   * is left out unless a test is about it.
   */
  private static CssRoleCreateRequest createRequest(
      String code, String roleName, boolean district, boolean client) {
    return new CssRoleCreateRequest(code, roleName, null, district, client);
  }

  private static CssRoleCreateRequest createRequest(
      String code, String roleName, String description, boolean district, boolean client) {
    return new CssRoleCreateRequest(code, roleName, description, district, client);
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
    assertThat(created.displayName()).isEqualTo("FREP Administrator");
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
  void acceptsBothScopes() {
    // A role may be scoped by a district AND a forest client; it is granted
    // against the pair. Each scope contributes its own marker to the composite
    // chain, and a role carrying only one marker would advertise only one
    // picker on the grant screen.
    givenRoles();

    service.createRole(INTEGRATION, ENV,
        createRequest("R_ONE", "Role one", true, true), DEFINER);

    verify(cssApiService).createRole(INTEGRATION, ENV, CssRoleNaming.MARKER_DISTRICT);
    verify(cssApiService).createRole(INTEGRATION, ENV, CssRoleNaming.MARKER_FOREST_CLIENT);
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
        DEFINER, INTEGRATION, ENV, "FREP_ADMINISTRATOR", "FREP Administrator", null, List.of("DISTRICT"));
  }

  @Test
  @DisplayName("audits the normalised code, not what was typed")
  void auditsNormalisedCode() {
    // The trail has to name the role that exists, or it cannot be matched to it.
    givenRoles();

    service.createRole(INTEGRATION, ENV,
        createRequest(" frep_administrator ", " FREP Administrator ", false, false), DEFINER);

    verify(auditWriteService).storeRoleCreated(
        DEFINER, INTEGRATION, ENV, "FREP_ADMINISTRATOR", "FREP Administrator", null, List.of());
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
        any(), anyInt(), anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("writes no audit record when the code was rejected")
  void noAuditWhenCodeRejected() {
    givenRoles(role("R_ONE", false));

    assertThatThrownBy(() -> service.createRole(INTEGRATION, ENV,
        createRequest("R_ONE", "Role one", false, false), DEFINER))
        .isInstanceOf(FamHttpException.class);

    verify(auditWriteService, never()).storeRoleCreated(
        any(), anyInt(), anyString(), anyString(), anyString(), any(), any());
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
      assertThat(option.displayName()).isEqualTo("FREP Administrator");
      assertThat(option.roleTypeDistrict()).isTrue();
    });
  }

  // ------------------------------------------------------------------ assignment

  private CssUserRoleAssignmentRequest request(String scopeType, List<String> values) {
    return new CssUserRoleAssignmentRequest(
        "AABBCCDDEEFF00112233445566778899", UserType.IDIR, "CHR_FREP_EDITOR",
        "jane@gov.bc.ca", selections(scopeType, values));
  }

  /** One scope dimension, or none when the test is exercising an unscoped role. */
  private static List<CssScopeSelection> selections(String scopeType, List<String> values) {
    return scopeType == null || values.isEmpty()
        ? List.of()
        : List.of(new CssScopeSelection(scopeType, values));
  }

  /**
   * An application administrator of the integration under test.
   *
   * <p>Holds a real tier rather than a placeholder role: since delegations were
   * introduced the grant path checks what the requester may grant, and a
   * requester with no tier is refused before the guard under test is reached.
   */
  /**
   * An application administrator, for the tests that exercise grant mechanics
   * rather than authorisation. Required since the grant path started checking
   * what the requester may grant - there is no unauthenticated grant any more.
   */
  private static final Requester GRANTER = Requester.builder()
      .userName("GRANTER").userGuid("EEEE1111")
      .accessRoles(List.of(FamAdminRole.appAdmin(INTEGRATION, ENV)))
      .build();

  private static Requester requesterWithGuid(String guid) {
    return Requester.builder()
        .userName("JSMITH").userGuid(guid)
        .accessRoles(List.of(FamAdminRole.appAdmin(INTEGRATION, ENV)))
        .build();
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
        any(), anyString(), any(UserType.class), anyInt(), anyString(), anyString(), any());
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

    assertThat(service.assignUserRoles(INTEGRATION, ENV, request(null, List.of()), GRANTER))
        .singleElement()
        .satisfies(r -> assertThat(r.assigned()).isTrue());
  }

  @Test
  @DisplayName("assigns an unscoped role as-is")
  void assignsUnscopedRoleAsIs() {
    givenRoles(role("CHR_FREP_EDITOR", false));

    List<CssUserRoleAssignmentResult> results =
        service.assignUserRoles(INTEGRATION, ENV, request(null, List.of()), GRANTER);

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
        service.assignUserRoles(INTEGRATION, ENV, request("DISTRICT", List.of("DCC", "DQU")), GRANTER);

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
        service.assignUserRoles(INTEGRATION, ENV, request("DISTRICT", List.of("DCC")), GRANTER);

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
        INTEGRATION, ENV, request("DISTRICT", List.of("DCC", "DCC")), GRANTER))
        .hasSize(1);
  }

  @Test
  @DisplayName("one failing scope role does not discard the others")
  void oneFailingRoleDoesNotDiscardOthers() {
    givenRoles(role("CHR_FREP_EDITOR", false));
    doThrow(new RuntimeException("CSS exploded"))
        .when(cssApiService).createRole(INTEGRATION, ENV, "CHR_FREP_EDITOR_DISTRICT-DQU");

    List<CssUserRoleAssignmentResult> results =
        service.assignUserRoles(INTEGRATION, ENV, request("DISTRICT", List.of("DCC", "DQU")), GRANTER);

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
        service.assignUserRoles(INTEGRATION, ENV, request("DISTRICT", List.of("DCC")), GRANTER);

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
    // Values with no type used to be caught by a scope_type check. The shape
    // changed but the accident did not: untyped values would fall through and
    // grant the unscoped base role, which is a wider grant than was asked for.
    CssUserRoleAssignmentRequest untyped = new CssUserRoleAssignmentRequest(
        "AABBCCDDEEFF00112233445566778899", UserType.IDIR, "CHR_FREP_EDITOR",
        "jane@gov.bc.ca", List.of(new CssScopeSelection(" ", List.of("DCC"))));

    assertThatThrownBy(() -> service.assignUserRoles(INTEGRATION, ENV, untyped, GRANTER))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("name its type");

    verify(cssApiService, never()).assignUserRoles(anyInt(), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("grants every combination when a role is scoped by two things")
  void grantsTheCrossProductOfScopes() {
    // Being a submitter for DCC and for client 00001012 is not the same as
    // being one for either, so each pair is its own role.
    givenRoles(role("CHR_FREP_EDITOR", false));
    when(cssApiService.createRole(anyInt(), anyString(), anyString())).thenReturn(true);

    service.assignUserRoles(INTEGRATION, ENV, new CssUserRoleAssignmentRequest(
        "AABBCCDDEEFF00112233445566778899", UserType.IDIR, "CHR_FREP_EDITOR", null,
        List.of(new CssScopeSelection("DISTRICT", List.of("DCC", "DKA")),
            new CssScopeSelection("FOREST_CLIENT", List.of("00001012")))), GRANTER);

    verify(cssApiService).createRole(
        INTEGRATION, ENV, "CHR_FREP_EDITOR_DISTRICT-DCC_FOREST_CLIENT-00001012");
    verify(cssApiService).createRole(
        INTEGRATION, ENV, "CHR_FREP_EDITOR_DISTRICT-DKA_FOREST_CLIENT-00001012");
  }

  @Test
  @DisplayName("refuses a combination count that would run away")
  void refusesTooManyCombinations() {
    // Ten districts and twenty clients is two hundred CSS roles, each a create
    // and an assign. Better to refuse than to spend minutes on a mistake.
    List<String> districts = java.util.stream.IntStream.range(0, 10)
        .mapToObj(i -> "D" + i).toList();
    List<String> clients = java.util.stream.IntStream.range(0, 20)
        .mapToObj(i -> "C" + i).toList();

    CssUserRoleAssignmentRequest tooMany = new CssUserRoleAssignmentRequest(
        "AABBCCDDEEFF00112233445566778899", UserType.IDIR, "CHR_FREP_EDITOR", null,
        List.of(new CssScopeSelection("DISTRICT", districts),
            new CssScopeSelection("FOREST_CLIENT", clients)));

    assertThatThrownBy(() -> service.assignUserRoles(INTEGRATION, ENV, tooMany, GRANTER))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("200")
        .hasMessageContaining("smaller batches");

    verify(cssApiService, never()).assignUserRoles(anyInt(), anyString(), anyString(), any());
  }

  // --------------------------------------------------------------------- revoke

  private static CssUserRoleRevokeRequest revokeRequest(
      String scopeType, String scopeValue) {
    return new CssUserRoleRevokeRequest(
        "AABBCCDDEEFF00112233445566778899", UserType.IDIR, "CHR_FREP_EDITOR",
        scopeType == null ? List.of() : List.of(
            new CssScopeSelection(scopeType, List.of(scopeValue))));
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
        eq(DEFINER), eq("AABBCCDDEEFF00112233445566778899"), eq(UserType.IDIR),
        eq(INTEGRATION), eq(ENV), eq("CHR_FREP_EDITOR"),
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
        any(), anyString(), any(UserType.class), anyInt(), anyString(), anyString(), any());
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
          assertThat(row.roleDisplayName()).isEqualTo("FREP Administrator");
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
          assertThat(row.roleDisplayName()).isEqualTo("Submitter (CHR)");
          assertThat(row.scopes())
              .containsExactly(new ScopeDto("DISTRICT", "DCC", null));
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
        .satisfies(row -> assertThat(row.roleDisplayName()).isNull());
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
          assertThat(row.scopes())
              .containsExactly(new ScopeDto("DISTRICT", "DCC", null));
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

  // ---------------------------------------------------------------------------
  // Role deletion
  // ---------------------------------------------------------------------------

  private static CssApiService.CssUserDto user(String username) {
    return new CssApiService.CssUserDto(username, null, null, null, null);
  }

  @Test
  @DisplayName("deleting a role removes its sidecar too, but never the scope marker")
  void deleteRemovesSidecarNotMarker() {
    givenRoles(
        role("CHR_FREP_EDITOR", true),
        role("HAS_DISTRICT_ROLE", false),
        role("FAM:LABEL:CHR_FREP_EDITOR:Submitter (CHR)", false));

    CssRoleDeleteResultDto result =
        service.deleteRole(INTEGRATION, ENV, "CHR_FREP_EDITOR", DEFINER);

    assertThat(result.removedRoles())
        .containsExactlyInAnyOrder(
            "CHR_FREP_EDITOR", "FAM:LABEL:CHR_FREP_EDITOR:Submitter (CHR)");

    // The marker is shared by every scoped role on the integration.
    verify(cssApiService, never()).deleteRole(INTEGRATION, ENV, "HAS_DISTRICT_ROLE");
  }

  @Test
  @DisplayName("deleting a scoped role takes the per-scope roles with it")
  void deleteRemovesDerivedRoles() {
    givenRoles(
        role("CHR_FREP_EDITOR", true),
        role("CHR_FREP_EDITOR_DISTRICT-DCC", false),
        role("CHR_FREP_EDITOR_DISTRICT-DKA", false),
        role("HAS_DISTRICT_ROLE", false));

    CssRoleDeleteResultDto result =
        service.deleteRole(INTEGRATION, ENV, "CHR_FREP_EDITOR", DEFINER);

    assertThat(result.removedRoles()).containsExactlyInAnyOrder(
        "CHR_FREP_EDITOR", "CHR_FREP_EDITOR_DISTRICT-DCC", "CHR_FREP_EDITOR_DISTRICT-DKA");
  }

  @Test
  @DisplayName("a role whose name merely starts the same is left alone")
  void deleteDoesNotMatchOnPrefix() {
    givenRoles(role("FREP_EDITOR", false), role("FREP_EDITOR_EXTRA", false));

    CssRoleDeleteResultDto result = service.deleteRole(INTEGRATION, ENV, "FREP_EDITOR", DEFINER);

    assertThat(result.removedRoles()).containsExactly("FREP_EDITOR");
    verify(cssApiService, never()).deleteRole(INTEGRATION, ENV, "FREP_EDITOR_EXTRA");
  }

  @Test
  @DisplayName("counts people once when they hold the role in several scopes")
  void deleteCountsDistinctMembers() {
    givenRoles(
        role("CHR_FREP_EDITOR", true),
        role("CHR_FREP_EDITOR_DISTRICT-DCC", false),
        role("CHR_FREP_EDITOR_DISTRICT-DKA", false));
    when(cssApiService.getUsersWithRole(INTEGRATION, ENV, "CHR_FREP_EDITOR_DISTRICT-DCC"))
        .thenReturn(List.of(user("abc@azureidir"), user("def@azureidir")));
    when(cssApiService.getUsersWithRole(INTEGRATION, ENV, "CHR_FREP_EDITOR_DISTRICT-DKA"))
        .thenReturn(List.of(user("abc@azureidir")));

    assertThat(service.deleteRole(INTEGRATION, ENV, "CHR_FREP_EDITOR", DEFINER).membersAffected())
        .isEqualTo(2);
  }

  @Test
  @DisplayName("the member count is taken before anything is deleted")
  void deleteCountsBeforeDeleting() {
    givenRoles(role("FREP_EDITOR", false));
    when(cssApiService.getUsersWithRole(INTEGRATION, ENV, "FREP_EDITOR"))
        .thenReturn(List.of(user("abc@azureidir")));

    service.deleteRole(INTEGRATION, ENV, "FREP_EDITOR", DEFINER);

    // Counting after the deletion would always report zero: CSS keeps no trace
    // of who held a role once it is gone.
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(cssApiService);
    order.verify(cssApiService).getUsersWithRole(INTEGRATION, ENV, "FREP_EDITOR");
    order.verify(cssApiService).deleteRole(INTEGRATION, ENV, "FREP_EDITOR");
  }

  @Test
  @DisplayName("refuses to delete a scope marker")
  void deleteRefusesMarker() {
    assertThatThrownBy(() -> service.deleteRole(INTEGRATION, ENV, "HAS_DISTRICT_ROLE", DEFINER))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("scope marker");

    verify(cssApiService, never()).deleteRole(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("refuses to delete a role that does not exist")
  void deleteRefusesUnknownRole() {
    givenRoles(role("FREP_EDITOR", false));

    assertThatThrownBy(() -> service.deleteRole(INTEGRATION, ENV, "NOPE", DEFINER))
        .isInstanceOf(FamHttpException.class);

    verify(cssApiService, never()).deleteRole(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("audits the deletion with the count captured before it")
  void deleteIsAudited() {
    givenRoles(
        role("FREP_EDITOR", false),
        role("FAM:LABEL:FREP_EDITOR:FREP Editor", false));
    when(cssApiService.getUsersWithRole(INTEGRATION, ENV, "FREP_EDITOR"))
        .thenReturn(List.of(user("abc@azureidir"), user("def@azureidir")));

    service.deleteRole(INTEGRATION, ENV, "FREP_EDITOR", DEFINER);

    verify(auditWriteService).storeRoleDeleted(
        eq(DEFINER), eq(INTEGRATION), eq(ENV), eq("FREP_EDITOR"), eq("FREP Editor"),
        any(), eq(2));
  }

  @Test
  @DisplayName("a partial failure reports what was already removed")
  void deletePartialFailureNamesWhatWent() {
    givenRoles(
        role("CHR_FREP_EDITOR", true),
        role("CHR_FREP_EDITOR_DISTRICT-DCC", false));
    doThrow(new RuntimeException("boom"))
        .when(cssApiService).deleteRole(INTEGRATION, ENV, "CHR_FREP_EDITOR");

    // Derived roles go first, so by the time this fails the access is gone.
    assertThatThrownBy(() -> service.deleteRole(INTEGRATION, ENV, "CHR_FREP_EDITOR", DEFINER))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("CHR_FREP_EDITOR_DISTRICT-DCC");

    verify(auditWriteService, never()).storeRoleDeleted(
        any(), anyInt(), anyString(), anyString(), any(), any(), anyInt());
  }

  // ---------------------------------------------------------------------------
  // Member counts
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("member counts fold derived roles into their base role")
  void memberCountsFoldDerivedRoles() {
    givenRoles(
        role("CHR_FREP_EDITOR", true),
        role("CHR_FREP_EDITOR_DISTRICT-DCC", false),
        role("HAS_DISTRICT_ROLE", false),
        role("FAM:LABEL:CHR_FREP_EDITOR:Submitter (CHR)", false));
    when(cssApiService.getUsersWithRole(INTEGRATION, ENV, "CHR_FREP_EDITOR_DISTRICT-DCC"))
        .thenReturn(List.of(user("abc@azureidir")));

    assertThat(service.getRoleMemberCounts(INTEGRATION, ENV))
        .singleElement()
        .satisfies(count -> {
          assertThat(count.roleName()).isEqualTo("CHR_FREP_EDITOR");
          assertThat(count.memberCount()).isEqualTo(1);
        });

    // Neither a sidecar nor a marker is something a person holds.
    verify(cssApiService, never()).getUsersWithRole(
        INTEGRATION, ENV, "FAM:LABEL:CHR_FREP_EDITOR:Submitter (CHR)");
    verify(cssApiService, never()).getUsersWithRole(INTEGRATION, ENV, "HAS_DISTRICT_ROLE");
  }

  // ---------------------------------------------------------------------------
  // Creating a role in every environment
  // ---------------------------------------------------------------------------

  private void givenIntegration(String... environments) {
    when(cssApiService.getIntegrations()).thenReturn(List.of(
        new CssIntegrationDto(INTEGRATION, "FREP", null, List.of(environments),
            "applied", null, null)));
  }

  private void givenRolesIn(String environment, CssRoleDto... roles) {
    when(cssApiService.getRoles(INTEGRATION, environment)).thenReturn(List.of(roles));
  }

  @Test
  @DisplayName("creates the role in every environment the integration has")
  void createsInEveryEnvironment() {
    givenIntegration("dev", "test", "prod");
    for (String env : List.of("dev", "test", "prod")) {
      givenRolesIn(env);
    }

    CssRoleBulkCreateResultDto result = service.createRoleInAllEnvironments(
        INTEGRATION, createRequest("FREP_ADMINISTRATOR", "FREP Administrator", false, false),
        DEFINER);

    assertThat(result.environments()).containsExactly("dev", "test", "prod");
    for (String env : List.of("dev", "test", "prod")) {
      verify(cssApiService).createRole(INTEGRATION, env, "FREP_ADMINISTRATOR");
      verify(cssApiService).createRole(
          INTEGRATION, env, "FAM:LABEL:FREP_ADMINISTRATOR:FREP Administrator");
    }
  }

  @Test
  @DisplayName("uses the integration's own environments, not an assumed dev/test/prod")
  void usesTheIntegrationsEnvironments() {
    givenIntegration("dev", "test");
    givenRolesIn("dev");
    givenRolesIn("test");

    assertThat(service.createRoleInAllEnvironments(
        INTEGRATION, createRequest("FREP_ADMINISTRATOR", "FREP Administrator", false, false),
        DEFINER).environments())
        .containsExactly("dev", "test");

    // Asking CSS for an environment the integration does not have would fail.
    verify(cssApiService, never()).createRole(eq(INTEGRATION), eq("prod"), anyString());
  }

  @Test
  @DisplayName("creates nothing when the code is taken in any one environment")
  void refusesWhenTakenAnywhere() {
    givenIntegration("dev", "test", "prod");
    givenRolesIn("dev");
    givenRolesIn("test");
    givenRolesIn("prod", role("FREP_ADMINISTRATOR", false));

    assertThatThrownBy(() -> service.createRoleInAllEnvironments(
        INTEGRATION, createRequest("FREP_ADMINISTRATOR", "FREP Administrator", false, false),
        DEFINER))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("prod");

    // Not even the environments that were free: a half-created code cannot be
    // fixed from the screen, because creating again fails on the one that has it.
    verify(cssApiService, never()).createRole(anyInt(), anyString(), anyString());
    verify(auditWriteService, never()).storeRoleCreated(
        any(), anyInt(), anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("rolls back earlier environments when a later one fails")
  void rollsBackEarlierEnvironments() {
    givenIntegration("dev", "test", "prod");
    givenRolesIn("dev");
    givenRolesIn("test");
    givenRolesIn("prod");
    doThrow(new RuntimeException("upstream down"))
        .when(cssApiService).createRole(INTEGRATION, "prod", "FREP_ADMINISTRATOR");

    assertThatThrownBy(() -> service.createRoleInAllEnvironments(
        INTEGRATION, createRequest("FREP_ADMINISTRATOR", "FREP Administrator", false, false),
        DEFINER))
        .isInstanceOf(RuntimeException.class);

    // Nobody can hold a role created seconds ago, so undoing it costs nothing -
    // and leaves the code free to try again.
    verify(cssApiService).deleteRole(INTEGRATION, "dev", "FREP_ADMINISTRATOR");
    verify(cssApiService).deleteRole(INTEGRATION, "test", "FREP_ADMINISTRATOR");
  }

  @Test
  @DisplayName("writes no audit for a creation that was rolled back")
  void noAuditWhenRolledBack() {
    givenIntegration("dev", "test");
    givenRolesIn("dev");
    givenRolesIn("test");
    doThrow(new RuntimeException("upstream down"))
        .when(cssApiService).createRole(INTEGRATION, "test", "FREP_ADMINISTRATOR");

    assertThatThrownBy(() -> service.createRoleInAllEnvironments(
        INTEGRATION, createRequest("FREP_ADMINISTRATOR", "FREP Administrator", false, false),
        DEFINER))
        .isInstanceOf(RuntimeException.class);

    // dev succeeded and was undone; an audit row claiming it would be a lie.
    verify(auditWriteService, never()).storeRoleCreated(
        any(), anyInt(), anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("audits each environment once all of them have succeeded")
  void auditsEveryEnvironment() {
    givenIntegration("dev", "test");
    givenRolesIn("dev");
    givenRolesIn("test");

    service.createRoleInAllEnvironments(
        INTEGRATION, createRequest("CHR_FREP_EDITOR", "Submitter (CHR)", true, false), DEFINER);

    verify(auditWriteService).storeRoleCreated(
        DEFINER, INTEGRATION, "dev", "CHR_FREP_EDITOR", "Submitter (CHR)", null, List.of("DISTRICT"));
    verify(auditWriteService).storeRoleCreated(
        DEFINER, INTEGRATION, "test", "CHR_FREP_EDITOR", "Submitter (CHR)", null, List.of("DISTRICT"));
  }

  @Test
  @DisplayName("scopes the role in every environment, marker and all")
  void scopesEveryEnvironment() {
    givenIntegration("dev", "test");
    givenRolesIn("dev");
    givenRolesIn("test");

    service.createRoleInAllEnvironments(
        INTEGRATION, createRequest("CHR_FREP_EDITOR", "Submitter (CHR)", true, false), DEFINER);

    for (String env : List.of("dev", "test")) {
      verify(cssApiService).createRole(INTEGRATION, env, "HAS_DISTRICT_ROLE");
      verify(cssApiService).addRoleComposites(
          INTEGRATION, env, "CHR_FREP_EDITOR", List.of("HAS_DISTRICT_ROLE"));
    }
  }

  @Test
  @DisplayName("refuses an integration that has no environments")
  void refusesIntegrationWithoutEnvironments() {
    when(cssApiService.getIntegrations()).thenReturn(List.of(
        new CssIntegrationDto(INTEGRATION, "FREP", null, null, "applied", null, null)));

    assertThatThrownBy(() -> service.createRoleInAllEnvironments(
        INTEGRATION, createRequest("FREP_ADMINISTRATOR", "FREP Administrator", false, false),
        DEFINER))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("no environments");
  }

  @Test
  @DisplayName("validates the role code before touching any environment")
  void validatesBeforeAnyEnvironment() {
    assertThatThrownBy(() -> service.createRoleInAllEnvironments(
        INTEGRATION, createRequest("HAS_DISTRICT_ROLE", "Marker", false, false), DEFINER))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("reserved");

    verify(cssApiService, never()).getIntegrations();
  }

  @Test
  @DisplayName("creates a second sidecar for the long description")
  void createsDescriptionSidecar() {
    givenRoles();

    service.createRole(INTEGRATION, ENV, createRequest(
        "FSPTS_VIEW_ALL", "View All",
        "Allows users to view all the FSPs but not edit", false, false), DEFINER);

    verify(cssApiService).createRole(INTEGRATION, ENV, "FSPTS_VIEW_ALL");
    verify(cssApiService).createRole(INTEGRATION, ENV, "FAM:LABEL:FSPTS_VIEW_ALL:View All");
    verify(cssApiService).createRole(INTEGRATION, ENV,
        "FAM:DESC:FSPTS_VIEW_ALL:Allows users to view all the FSPs but not edit");
  }

  @Test
  @DisplayName("creates no description sidecar when none was given")
  void noDescriptionNoSidecar() {
    givenRoles();

    service.createRole(INTEGRATION, ENV,
        createRequest("FSPTS_VIEW_ALL", "View All", "  ", false, false), DEFINER);

    // An empty sidecar would read back as a blank description rather than none.
    verify(cssApiService, never()).createRole(
        eq(INTEGRATION), eq(ENV), startsWith("FAM:DESC:"));
  }

  @Test
  @DisplayName("deleting a role removes both of its sidecars")
  void deleteRemovesBothSidecars() {
    givenRoles(
        role("FSPTS_VIEW_ALL", false),
        role("FAM:LABEL:FSPTS_VIEW_ALL:View All", false),
        role("FAM:DESC:FSPTS_VIEW_ALL:Allows users to view all the FSPs but not edit", false));

    // Leaving either behind would describe a role that no longer exists.
    assertThat(service.deleteRole(INTEGRATION, ENV, "FSPTS_VIEW_ALL", DEFINER).removedRoles())
        .containsExactlyInAnyOrder(
            "FSPTS_VIEW_ALL",
            "FAM:LABEL:FSPTS_VIEW_ALL:View All",
            "FAM:DESC:FSPTS_VIEW_ALL:Allows users to view all the FSPs but not edit");
  }

  @Test
  @DisplayName("a description sidecar is never offered as a grantable role")
  void descriptionSidecarIsNotSelectable() {
    givenRoles(
        role("FSPTS_VIEW_ALL", false),
        role("FAM:DESC:FSPTS_VIEW_ALL:Allows users to view all the FSPs but not edit", false));

    assertThat(service.getRoles(INTEGRATION, ENV))
        .extracting(CssRoleOptionDto::name)
        .containsExactly("FSPTS_VIEW_ALL");
  }

  @Test
  @DisplayName("the assignment listing labels a row with the role's short name")
  void assignmentRowUsesDisplayName() {
    givenRoles(
        role("FSPTS_VIEW_ALL", false),
        role("FAM:LABEL:FSPTS_VIEW_ALL:View All", false),
        role("FAM:DESC:FSPTS_VIEW_ALL:Allows users to view all the FSPs but not edit", false));
    when(cssApiService.getUsersWithRole(INTEGRATION, ENV, "FSPTS_VIEW_ALL"))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "abc@azureidir", "Jane", "Smith", "jane@gov.bc.ca", null)));

    // The pill shows the name, not the sentence - a sentence would not fit.
    assertThat(service.getUserRoleAssignments(INTEGRATION, ENV, DEFINER))
        .singleElement()
        .satisfies(row -> assertThat(row.roleDisplayName()).isEqualTo("View All"));
  }

  // ---------------------------------------------------------------------------
  // Administrators (Delegated admins / Application admins tabs)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("reads administrators from FAM's own integration, not the application's")
  void administratorsComeFromFamsIntegration() {
    when(cssApiService.getUsersWithRole(anyInt(), anyString(), anyString()))
        .thenReturn(List.of());

    service.getAdministrators(INTEGRATION, ENV, AdminRoleAuthGroup.APP_ADMIN);

    // The role sits on FAM's client; the application is named inside the role.
    // Reading the application's own integration would always come back empty.
    verify(cssApiService).getUsersWithRole(
        FAM_OWN_INTEGRATION, "dev", "APP_ADMIN_" + INTEGRATION + "_DEV");
    verify(cssApiService, never()).getUsersWithRole(
        eq(INTEGRATION), anyString(), anyString());
  }

  @Test
  @DisplayName("the delegated roster unions everyone holding any delegation")
  void delegatedTabUnionsEveryDelegation() {
    // A delegated administrator holds one role per delegation, so listing the
    // plain tier marker alone would show almost nobody.
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev")).thenReturn(List.of(
        role("DELEGATED_ADMIN_" + INTEGRATION + "_DEV__CHR_FREP_EDITOR", false),
        role("DELEGATED_ADMIN_" + INTEGRATION + "_DEV__FREP_VIEWER", false),
        role("DELEGATED_ADMIN_99999_DEV__OTHER_APP_ROLE", false),
        role("APP_ADMIN_" + INTEGRATION + "_DEV", false)));
    when(cssApiService.getUsersWithRole(anyInt(), anyString(), anyString()))
        .thenReturn(List.of());

    service.getAdministrators(INTEGRATION, ENV, AdminRoleAuthGroup.DELEGATED_ADMIN);

    verify(cssApiService).getUsersWithRole(FAM_OWN_INTEGRATION, "dev",
        "DELEGATED_ADMIN_" + INTEGRATION + "_DEV__CHR_FREP_EDITOR");
    verify(cssApiService).getUsersWithRole(FAM_OWN_INTEGRATION, "dev",
        "DELEGATED_ADMIN_" + INTEGRATION + "_DEV__FREP_VIEWER");

    // Another application's delegations, and the app-admin role, are not ours.
    verify(cssApiService, never()).getUsersWithRole(FAM_OWN_INTEGRATION, "dev",
        "DELEGATED_ADMIN_99999_DEV__OTHER_APP_ROLE");
    verify(cssApiService, never()).getUsersWithRole(FAM_OWN_INTEGRATION, "dev",
        "APP_ADMIN_" + INTEGRATION + "_DEV");
  }

  @Test
  @DisplayName("a delegated row names the role that person may grant")
  void delegatedRowNamesItsRole() {
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev")).thenReturn(List.of(
        role("DELEGATED_ADMIN_" + INTEGRATION + "_DEV__CHR_FREP_EDITOR_DISTRICT-DCC", false)));
    when(cssApiService.getUsersWithRole(anyInt(), anyString(), anyString()))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "abc@azureidir", "Jane", "Smith", "jane@gov.bc.ca", null)));

    assertThat(service.getAdministrators(INTEGRATION, ENV, AdminRoleAuthGroup.DELEGATED_ADMIN))
        .singleElement()
        .satisfies(row -> {
          // The base name, without the scope suffix: the scope is its own field
          // so the row can be withdrawn without the client taking a name apart.
          assertThat(row.delegatedRoleName()).isEqualTo("CHR_FREP_EDITOR");
          assertThat(row.scopes())
              .containsExactly(new ScopeDto("DISTRICT", "DCC", null));
        });
  }

  @Test
  @DisplayName("a delegated row carries what the role is called, not only its code")
  void delegatedRowCarriesTheRoleLabel() {
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev")).thenReturn(List.of(
        role("DELEGATED_ADMIN_" + INTEGRATION + "_DEV__CHR_FREP_EDITOR", false)));
    // The label sidecar sits beside the role it names, on the APPLICATION's
    // integration - not on FAM's own, where the delegation lives.
    givenRoles(role("FAM:LABEL:CHR_FREP_EDITOR:Submitter (CHR)", false));
    when(cssApiService.getUsersWithRole(anyInt(), anyString(), anyString()))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "abc@azureidir", "Jane", "Smith", "jane@gov.bc.ca", null)));

    assertThat(service.getAdministrators(INTEGRATION, ENV, AdminRoleAuthGroup.DELEGATED_ADMIN))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.delegatedRoleDisplayName()).isEqualTo("Submitter (CHR)");
          // Still carried: it is what the withdrawal has to name.
          assertThat(row.delegatedRoleName()).isEqualTo("CHR_FREP_EDITOR");
        });
  }

  @Test
  @DisplayName("a role with no label sidecar is left unnamed for the client to fall back on")
  void delegatedRowWithoutALabel() {
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev")).thenReturn(List.of(
        role("DELEGATED_ADMIN_" + INTEGRATION + "_DEV__CHR_FREP_EDITOR", false)));
    // Every role added by hand in the CSS console looks like this.
    givenRoles(role("CHR_FREP_EDITOR", false));
    when(cssApiService.getUsersWithRole(anyInt(), anyString(), anyString()))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "abc@azureidir", "Jane", "Smith", "jane@gov.bc.ca", null)));

    assertThat(service.getAdministrators(INTEGRATION, ENV, AdminRoleAuthGroup.DELEGATED_ADMIN))
        .singleElement()
        .satisfies(row -> assertThat(row.delegatedRoleDisplayName()).isNull());
  }

  @Test
  @DisplayName("the application admin tab spends no call looking up role labels")
  void appAdminRosterDoesNotReadApplicationRoles() {
    when(cssApiService.getUsersWithRole(anyInt(), anyString(), anyString()))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "abc@azureidir", "Jane", "Smith", "jane@gov.bc.ca", null)));

    service.getAdministrators(INTEGRATION, ENV, AdminRoleAuthGroup.APP_ADMIN);

    // An application administrator is delegated no role, so there is nothing to
    // name and no reason to fetch the application's roles.
    verify(cssApiService, never()).getRoles(INTEGRATION, ENV);
  }

  @Test
  @DisplayName("an application administrator row is delegated no role and no scope")
  void appAdminRowHasNoDelegation() {
    when(cssApiService.getUsersWithRole(anyInt(), anyString(), anyString()))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "abc@azureidir", "Jane", "Smith", "jane@gov.bc.ca", null)));

    assertThat(service.getAdministrators(INTEGRATION, ENV, AdminRoleAuthGroup.APP_ADMIN))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.delegatedRoleName()).isNull();
          assertThat(row.scopes()).isEmpty();
        });
  }

  @Test
  @DisplayName("a compound delegation keeps both scopes, so the row can name itself")
  void delegatedRowKeepsCompoundScopes() {
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev")).thenReturn(List.of(
        role("DELEGATED_ADMIN_" + INTEGRATION
            + "_DEV__CHR_FREP_EDITOR_DISTRICT-DCC_FOREST_CLIENT-00001012", false)));
    when(cssApiService.getUsersWithRole(anyInt(), anyString(), anyString()))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "abc@azureidir", "Jane", "Smith", "jane@gov.bc.ca", null)));

    assertThat(service.getAdministrators(INTEGRATION, ENV, AdminRoleAuthGroup.DELEGATED_ADMIN))
        .singleElement()
        .satisfies(row -> assertThat(row.scopes()).containsExactly(
            new ScopeDto("DISTRICT", "DCC", null),
            new ScopeDto("FOREST_CLIENT", "00001012", null)));
  }

  @Test
  @DisplayName("a row withdrawn as it was listed removes exactly the role it came from")
  void rowRoundTripsToItsOwnDelegation() {
    // The whole reason the row carries a base name and scopes rather than the
    // joined string. If the two disagreed, the withdrawal would name a role
    // nobody holds and would silently remove nothing.
    String delegation = "DELEGATED_ADMIN_" + INTEGRATION
        + "_DEV__CHR_FREP_EDITOR_DISTRICT-DCC_FOREST_CLIENT-00001012";
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev"))
        .thenReturn(List.of(role(delegation, false)));
    when(cssApiService.getUsersWithRole(anyInt(), anyString(), anyString()))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "abc@azureidir", "Jane", "Smith", "jane@gov.bc.ca", null)));

    CssAdministratorRowDto row =
        service.getAdministrators(INTEGRATION, ENV, AdminRoleAuthGroup.DELEGATED_ADMIN).get(0);

    // Exactly what the table sends back when the trash button is confirmed.
    service.removeDelegatedAdmin(INTEGRATION, ENV,
        new CssDelegatedAdminRequest("AABBCCDDEEFF00112233445566778899", UserType.IDIR,
            row.delegatedRoleName(),
            row.scopes().stream()
                .map(scope -> new CssScopeSelection(scope.type(), List.of(scope.value())))
                .toList()),
        requesterWithGuid("SOMEONEELSE"));

    verify(cssApiService).removeUserRole(
        eq(FAM_OWN_INTEGRATION), eq("dev"), anyString(), eq(row.roleName()));
  }

  @Test
  @DisplayName("keeps environments apart - a dev tab must not list prod administrators")
  void administratorsAreEnvironmentSpecific() {
    when(cssApiService.getUsersWithRole(anyInt(), anyString(), anyString()))
        .thenReturn(List.of());

    service.getAdministrators(INTEGRATION, "prod", AdminRoleAuthGroup.APP_ADMIN);

    verify(cssApiService).getUsersWithRole(
        FAM_OWN_INTEGRATION, "dev", "APP_ADMIN_" + INTEGRATION + "_PROD");
  }

  @Test
  @DisplayName("names the administrator and recovers their GUID and domain")
  void administratorRowsAreNamed() {
    when(cssApiService.getUsersWithRole(anyInt(), anyString(), anyString()))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "aabbccddeeff00112233445566778899@azureidir",
            "Jane", "Smith", "jane@gov.bc.ca", null)));

    assertThat(service.getAdministrators(INTEGRATION, ENV, AdminRoleAuthGroup.APP_ADMIN))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.firstName()).isEqualTo("Jane");
          assertThat(row.email()).isEqualTo("jane@gov.bc.ca");
          assertThat(row.userGuid()).isEqualTo("AABBCCDDEEFF00112233445566778899");
          assertThat(row.domain()).isEqualTo("IDIR");
          assertThat(row.tier()).isEqualTo(AdminRoleAuthGroup.APP_ADMIN);
        });
  }

  @Test
  @DisplayName("refuses the FAM_ADMIN tier, which belongs to no single application")
  void famAdminTierIsRefused() {
    assertThatThrownBy(() -> service.getAdministrators(
        INTEGRATION, ENV, AdminRoleAuthGroup.FAM_ADMIN))
        .isInstanceOf(FamHttpException.class);
  }

  @Test
  @DisplayName("an administrative role that does not exist yet is an empty roster")
  void missingAdminRoleIsEmptyNotAnError() {
    // CSS answers 404 for a role nobody has been appointed to, which is the
    // normal state for most applications - not a failure to report.
    when(cssApiService.getUsersWithRole(anyInt(), anyString(), anyString()))
        .thenThrow(new ca.bc.gov.nrs.fam.exception.UpstreamException(
            org.springframework.http.HttpStatus.NOT_FOUND, "not_found",
            "Role not found", "css-api"));

    assertThat(service.getAdministrators(INTEGRATION, ENV, AdminRoleAuthGroup.DELEGATED_ADMIN))
        .isEmpty();
  }

  @Test
  @DisplayName("a real upstream failure is still reported")
  void otherUpstreamFailuresStillSurface() {
    when(cssApiService.getUsersWithRole(anyInt(), anyString(), anyString()))
        .thenThrow(new ca.bc.gov.nrs.fam.exception.UpstreamException(
            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "boom",
            "CSS is down", "css-api"));

    // Swallowing this would show an empty roster and hide an outage.
    assertThatThrownBy(() -> service.getAdministrators(
        INTEGRATION, ENV, AdminRoleAuthGroup.APP_ADMIN))
        .isInstanceOf(ca.bc.gov.nrs.fam.exception.UpstreamException.class);
  }

  // ---------------------------------------------------------------------------
  // Delegated administrators: what they may grant
  // ---------------------------------------------------------------------------

  /** A delegated administrator holding delegations for exactly these roles. */
  private static Requester delegatedFor(String... roleNames) {
    List<String> roles = new java.util.ArrayList<>();
    for (String roleName : roleNames) {
      roles.add(FamAdminRole.delegation(INTEGRATION, ENV, roleName));
    }
    return Requester.builder()
        .userName("DELEGATE").userGuid("DDDDEEEEFFFF00112233445566778899")
        .userType(UserType.IDIR)
        .accessRoles(roles)
        .build();
  }

  @Test
  @DisplayName("a delegated admin may grant the role they were delegated")
  void delegatedAdminMayGrantTheirRole() {
    givenRoles(role("CHR_FREP_EDITOR", false));

    assertThat(service.assignUserRoles(INTEGRATION, ENV, request(null, List.of()), delegatedFor("CHR_FREP_EDITOR")))
        .singleElement()
        .satisfies(result -> assertThat(result.assigned()).isTrue());
  }

  @Test
  @DisplayName("a delegated admin may not grant a role they were not delegated")
  void delegatedAdminMayNotGrantOtherRoles() {
    givenRoles(role("CHR_FREP_EDITOR", false));

    // The gap this closes: before delegations, any delegated administrator could
    // grant every role the application defined.
    assertThatThrownBy(() -> service.assignUserRoles(
        INTEGRATION, ENV, request(null, List.of()), delegatedFor("SOMETHING_ELSE")))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("CHR_FREP_EDITOR");

    verify(cssApiService, never()).assignUserRoles(anyInt(), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("a delegation is per scope value - one district does not carry another")
  void delegationIsPerScopeValue() {
    givenRoles(role("CHR_FREP_EDITOR", false));

    // Delegated DCC only; the request asks for DCC and DKA.
    assertThatThrownBy(() -> service.assignUserRoles(
        INTEGRATION, ENV, request("DISTRICT", List.of("DCC", "DKA")),
        delegatedFor("CHR_FREP_EDITOR_DISTRICT-DCC")))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("DKA");

    // All or nothing: DCC must not be granted just because it was allowed.
    verify(cssApiService, never()).assignUserRoles(anyInt(), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("a delegation for one scope value grants that one")
  void delegationAllowsItsOwnScopeValue() {
    givenRoles(role("CHR_FREP_EDITOR", false));

    assertThat(service.assignUserRoles(INTEGRATION, ENV,
        request("DISTRICT", List.of("DCC")),
        delegatedFor("CHR_FREP_EDITOR_DISTRICT-DCC")))
        .singleElement()
        .satisfies(result -> assertThat(result.assigned()).isTrue());
  }

  @Test
  @DisplayName("a delegation for another application does not carry over")
  void delegationIsPerApplication() {
    givenRoles(role("CHR_FREP_EDITOR", false));

    Requester elsewhere = Requester.builder()
        .userName("DELEGATE").userGuid("DDDD1111").userType(UserType.IDIR)
        .accessRoles(List.of(FamAdminRole.delegation(99999, "dev", "CHR_FREP_EDITOR")))
        .build();

    assertThatThrownBy(() -> service.assignUserRoles(
        INTEGRATION, ENV, request(null, List.of()), elsewhere))
        .isInstanceOf(FamHttpException.class);
  }

  @Test
  @DisplayName("an application administrator still grants anything")
  void appAdminIsUnrestricted() {
    givenRoles(role("CHR_FREP_EDITOR", false));

    // Delegations restrict the delegated tier only; they must not narrow the tier
    // that hands them out.
    assertThat(service.assignUserRoles(INTEGRATION, ENV, request(null, List.of()), requesterWithGuid("SOMEONEELSE")))
        .singleElement()
        .satisfies(result -> assertThat(result.assigned()).isTrue());
  }

  @Test
  @DisplayName("revoking is delegated the same way granting is")
  void revokeIsAlsoRestricted() {
    assertThatThrownBy(() -> service.revokeUserRole(
        INTEGRATION, ENV,
        new CssUserRoleRevokeRequest("AABBCCDDEEFF00112233445566778899", UserType.IDIR,
            "CHR_FREP_EDITOR", List.of()),
        delegatedFor("SOMETHING_ELSE")))
        .isInstanceOf(FamHttpException.class);

    verify(cssApiService, never()).removeUserRole(anyInt(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("holding a delegation makes somebody a delegated administrator")
  void delegationImpliesTheTier() {
    // The plain DELEGATED_ADMIN_<id>_<ENV> marker need not also be assigned, so
    // appointing is one role rather than two that must be kept in step.
    assertThat(delegatedFor("CHR_FREP_EDITOR").tierFor(INTEGRATION, ENV))
        .contains(AdminRoleAuthGroup.DELEGATED_ADMIN);
  }

  // ---------------------------------------------------------------------------
  // Appointing and removing delegated administrators
  // ---------------------------------------------------------------------------

  private static CssDelegatedAdminRequest appointment(String scopeType, List<String> values) {
    return new CssDelegatedAdminRequest(
        "AABBCCDDEEFF00112233445566778899", UserType.IDIR, "CHR_FREP_EDITOR",
        selections(scopeType, values));
  }

  @Test
  @DisplayName("appoints by creating the delegation on FAM's own integration")
  void appointCreatesTheDelegation() {
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev")).thenReturn(List.of());

    service.appointDelegatedAdmin(INTEGRATION, ENV, appointment(null, List.of()),
        requesterWithGuid("SOMEONEELSE"));

    String delegation = "DELEGATED_ADMIN_" + INTEGRATION + "_DEV__CHR_FREP_EDITOR";
    verify(cssApiService).createRole(FAM_OWN_INTEGRATION, "dev", delegation);
    verify(cssApiService).assignUserRoles(
        eq(FAM_OWN_INTEGRATION), eq("dev"), anyString(), eq(List.of(delegation)));

    // The application's own integration holds application roles, never these.
    verify(cssApiService, never()).createRole(eq(INTEGRATION), anyString(), anyString());
  }

  @Test
  @DisplayName("a scoped appointment is one delegation per scope value")
  void appointIsPerScopeValue() {
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev")).thenReturn(List.of());

    service.appointDelegatedAdmin(INTEGRATION, ENV,
        appointment("DISTRICT", List.of("DCC", "DKA")), requesterWithGuid("SOMEONEELSE"));

    // Delegating the bare base role would authorise nothing, since a scoped grant
    // only ever assigns per-scope roles.
    verify(cssApiService).createRole(FAM_OWN_INTEGRATION, "dev",
        "DELEGATED_ADMIN_" + INTEGRATION + "_DEV__CHR_FREP_EDITOR_DISTRICT-DCC");
    verify(cssApiService).createRole(FAM_OWN_INTEGRATION, "dev",
        "DELEGATED_ADMIN_" + INTEGRATION + "_DEV__CHR_FREP_EDITOR_DISTRICT-DKA");
  }

  @Test
  @DisplayName("refuses to appoint yourself")
  void appointRefusesSelf() {
    Requester self = Requester.builder()
        .userName("JSMITH").userGuid("AABBCCDDEEFF00112233445566778899")
        .accessRoles(List.of(FamAdminRole.appAdmin(INTEGRATION, ENV)))
        .build();

    assertThatThrownBy(() -> service.appointDelegatedAdmin(
        INTEGRATION, ENV, appointment(null, List.of()), self))
        .isInstanceOf(FamHttpException.class);

    verify(cssApiService, never()).createRole(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("a delegated admin may not appoint another one")
  void delegatedAdminMayNotAppoint() {
    // The one thing separating the tiers: otherwise a delegated admin could
    // promote anyone, themselves included.
    assertThatThrownBy(() -> service.appointDelegatedAdmin(
        INTEGRATION, ENV, appointment(null, List.of()), delegatedFor("CHR_FREP_EDITOR")))
        .isInstanceOf(FamHttpException.class);

    verify(cssApiService, never()).createRole(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("removing withdraws the assignment, leaving the delegation role in place")
  void removeWithdrawsTheAssignment() {
    service.removeDelegatedAdmin(INTEGRATION, ENV, appointment(null, List.of()),
        requesterWithGuid("SOMEONEELSE"));

    String delegation = "DELEGATED_ADMIN_" + INTEGRATION + "_DEV__CHR_FREP_EDITOR";
    verify(cssApiService).removeUserRole(
        eq(FAM_OWN_INTEGRATION), eq("dev"), anyString(), eq(delegation));

    // The role stays defined; others may hold it.
    verify(cssApiService, never()).deleteRole(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("appointing is audited against the application being administered")
  void appointIsAudited() {
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev")).thenReturn(List.of());

    service.appointDelegatedAdmin(INTEGRATION, ENV, appointment(null, List.of()),
        requesterWithGuid("SOMEONEELSE"));

    // Against FREP, not FAM: the change is "who may grant FREP's roles".
    verify(auditWriteService).storeCssGranted(
        any(), eq("AABBCCDDEEFF00112233445566778899"), any(UserType.class),
        eq(INTEGRATION), eq(ENV), eq("CHR_FREP_EDITOR"), any());
  }

  // ---------------------------------------------------------------------------
  // Deleting a role withdraws its delegations
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("deleting a role withdraws the delegations naming it")
  void deleteWithdrawsDelegations() {
    givenRoles(role("FSPTS_VIEW_ALL", false));
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev")).thenReturn(List.of(
        role("DELEGATED_ADMIN_" + INTEGRATION + "_DEV__FSPTS_VIEW_ALL", false)));

    CssRoleDeleteResultDto result =
        service.deleteRole(INTEGRATION, ENV, "FSPTS_VIEW_ALL", DEFINER);

    // An orphan is not inert: a grant creates a role it cannot find, so the
    // delegation would let its holder bring the deleted role back.
    verify(cssApiService).deleteRole(FAM_OWN_INTEGRATION, "dev",
        "DELEGATED_ADMIN_" + INTEGRATION + "_DEV__FSPTS_VIEW_ALL");
    assertThat(result.removedDelegations())
        .containsExactly("DELEGATED_ADMIN_" + INTEGRATION + "_DEV__FSPTS_VIEW_ALL");
  }

  @Test
  @DisplayName("per-scope delegations go with the role too")
  void deleteWithdrawsScopedDelegations() {
    givenRoles(role("CHR_FREP_EDITOR", false));
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev")).thenReturn(List.of(
        role("DELEGATED_ADMIN_" + INTEGRATION + "_DEV__CHR_FREP_EDITOR_DISTRICT-DCC", false),
        role("DELEGATED_ADMIN_" + INTEGRATION + "_DEV__CHR_FREP_EDITOR_DISTRICT-DKA", false)));

    assertThat(service.deleteRole(INTEGRATION, ENV, "CHR_FREP_EDITOR", DEFINER)
        .removedDelegations()).hasSize(2);
  }

  @Test
  @DisplayName("a delegation for a similarly named role is left alone")
  void deleteDoesNotWithdrawOtherRolesDelegations() {
    givenRoles(role("FREP_EDITOR", false));
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev")).thenReturn(List.of(
        role("DELEGATED_ADMIN_" + INTEGRATION + "_DEV__FREP_EDITOR_EXTRA", false)));

    assertThat(service.deleteRole(INTEGRATION, ENV, "FREP_EDITOR", DEFINER)
        .removedDelegations()).isEmpty();
  }

  @Test
  @DisplayName("another application's delegations are not ours to withdraw")
  void deleteDoesNotWithdrawAnotherApplicationsDelegations() {
    givenRoles(role("FSPTS_VIEW_ALL", false));
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev")).thenReturn(List.of(
        role("DELEGATED_ADMIN_99999_DEV__FSPTS_VIEW_ALL", false)));

    // Two applications may define a role of the same name.
    assertThat(service.deleteRole(INTEGRATION, ENV, "FSPTS_VIEW_ALL", DEFINER)
        .removedDelegations()).isEmpty();
  }

  @Test
  @DisplayName("delegations are withdrawn before the role itself")
  void delegationsGoFirst() {
    givenRoles(role("FSPTS_VIEW_ALL", false));
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev")).thenReturn(List.of(
        role("DELEGATED_ADMIN_" + INTEGRATION + "_DEV__FSPTS_VIEW_ALL", false)));

    service.deleteRole(INTEGRATION, ENV, "FSPTS_VIEW_ALL", DEFINER);

    // If this failed partway, the authority would already be gone rather than
    // left pointing at a role that no longer exists.
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(cssApiService);
    order.verify(cssApiService).deleteRole(FAM_OWN_INTEGRATION, "dev",
        "DELEGATED_ADMIN_" + INTEGRATION + "_DEV__FSPTS_VIEW_ALL");
    order.verify(cssApiService).deleteRole(INTEGRATION, ENV, "FSPTS_VIEW_ALL");
  }

  // ---------------------------------------------------------------------------
  // Appointing application administrators
  // ---------------------------------------------------------------------------

  private static CssAdministratorAppointRequest appointAdmin() {
    return new CssAdministratorAppointRequest(
        "AABBCCDDEEFF00112233445566778899", UserType.IDIR);
  }

  @Test
  @DisplayName("appoints an application administrator with no role or scope")
  void appointsApplicationAdmin() {
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev")).thenReturn(List.of());

    service.appointApplicationAdmin(INTEGRATION, ENV, appointAdmin(),
        requesterWithGuid("SOMEONEELSE"));

    // Authorised over the application, so there is nothing to name after the
    // environment - unlike a delegation.
    String roleName = "APP_ADMIN_" + INTEGRATION + "_DEV";
    verify(cssApiService).createRole(FAM_OWN_INTEGRATION, "dev", roleName);
    verify(cssApiService).assignUserRoles(
        eq(FAM_OWN_INTEGRATION), eq("dev"), anyString(), eq(List.of(roleName)));
  }

  @Test
  @DisplayName("reuses the role when it already exists")
  void appointReusesExistingRole() {
    String roleName = "APP_ADMIN_" + INTEGRATION + "_DEV";
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, "dev"))
        .thenReturn(List.of(role(roleName, false)));

    assertThat(service.appointApplicationAdmin(INTEGRATION, ENV, appointAdmin(),
        requesterWithGuid("SOMEONEELSE")).roleCreated()).isFalse();

    verify(cssApiService, never()).createRole(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("refuses to appoint yourself as an application administrator")
  void appointAdminRefusesSelf() {
    Requester self = Requester.builder()
        .userName("JSMITH").userGuid("AABBCCDDEEFF00112233445566778899")
        .accessRoles(List.of(FamAdminRole.appAdmin(INTEGRATION, ENV)))
        .build();

    assertThatThrownBy(() -> service.appointApplicationAdmin(
        INTEGRATION, ENV, appointAdmin(), self))
        .isInstanceOf(FamHttpException.class);

    verify(cssApiService, never()).assignUserRoles(anyInt(), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("a delegated admin may not appoint an application administrator")
  void delegatedAdminMayNotAppointAdmin() {
    assertThatThrownBy(() -> service.appointApplicationAdmin(
        INTEGRATION, ENV, appointAdmin(), delegatedFor("CHR_FREP_EDITOR")))
        .isInstanceOf(FamHttpException.class);
  }

  @Test
  @DisplayName("removing takes the role away from that person only")
  void removesApplicationAdmin() {
    service.removeApplicationAdmin(INTEGRATION, ENV, appointAdmin(),
        requesterWithGuid("SOMEONEELSE"));

    verify(cssApiService).removeUserRole(eq(FAM_OWN_INTEGRATION), eq("dev"), anyString(),
        eq("APP_ADMIN_" + INTEGRATION + "_DEV"));

    // The role stays defined; other administrators hold it.
    verify(cssApiService, never()).deleteRole(anyInt(), anyString(), anyString());
  }

  // -------------------------------------------------- FAM administering itself

  /** The roles that accumulate on FAM's own integration as admins are appointed. */
  private void givenFamOwnRoles() {
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, ENV)).thenReturn(List.of(
        role(FamAdminRole.FAM_ADMIN, false),
        role("APP_ADMIN_" + INTEGRATION + "_DEV", false),
        role("DELEGATED_ADMIN_" + INTEGRATION + "_DEV__CHR_FREP_EDITOR", false)));
    when(cssApiService.getUsersWithRole(eq(FAM_OWN_INTEGRATION), anyString(), anyString()))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "abc@azureidir", "Jane", "Smith", "jane@gov.bc.ca", null)));
  }

  @Test
  @DisplayName("FAM's own user list shows FAM administrators and nobody else")
  void famUserListShowsOnlyFamAdmins() {
    // Every application administrator and delegated administrator in the
    // province holds a role on FAM's integration - that is the only place a
    // role can sit and still reach FAM's token. Listing their holders here put
    // all of them on FAM's Users tab, where none of them belongs: they are
    // administrators OF something else, and have their own tabs for it.
    givenFamOwnRoles();

    assertThat(service.getUserRoleAssignments(FAM_OWN_INTEGRATION, ENV, DEFINER))
        .extracting(CssUserRoleRowDto::roleName)
        .containsExactly(FamAdminRole.FAM_ADMIN);
  }

  @Test
  @DisplayName("skips the other applications' admin roles before reading their holders")
  void famUserListDoesNotFanOutOverAdminRoles() {
    givenFamOwnRoles();

    service.getUserRoleAssignments(FAM_OWN_INTEGRATION, ENV, DEFINER);

    // One request per role is the expensive part of this read; filtering after
    // the fan-out would have cost the same as not filtering at all.
    verify(cssApiService, never()).getUsersWithRole(
        FAM_OWN_INTEGRATION, ENV, "APP_ADMIN_" + INTEGRATION + "_DEV");
    verify(cssApiService, never()).getUsersWithRole(
        FAM_OWN_INTEGRATION, ENV, "DELEGATED_ADMIN_" + INTEGRATION + "_DEV__CHR_FREP_EDITOR");
  }

  @Test
  @DisplayName("drops an admin role whose target cannot be parsed")
  void famUserListDropsMalformedAdminRoles() {
    // Typed by hand in the CSS console, so the integration id will not parse.
    // Keyed on the target rather than the tier, this one would have been kept -
    // which is the case the filter most needs to catch.
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, ENV)).thenReturn(List.of(
        role("APP_ADMIN_NOT_A_NUMBER", false)));
    when(cssApiService.getUsersWithRole(eq(FAM_OWN_INTEGRATION), anyString(), anyString()))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "abc@azureidir", "Jane", "Smith", "jane@gov.bc.ca", null)));

    assertThat(service.getUserRoleAssignments(FAM_OWN_INTEGRATION, ENV, DEFINER)).isEmpty();
  }

  @Test
  @DisplayName("another application's user list is untouched by the FAM rule")
  void otherApplicationsAreUnaffected() {
    // The rule is about FAM's integration, not about the role names. An
    // application that happened to define a role called APP_ADMIN_ something
    // still lists its holders.
    givenRoles(role("APP_ADMIN_" + INTEGRATION + "_DEV", false));
    when(cssApiService.getUsersWithRole(eq(INTEGRATION), anyString(), anyString()))
        .thenReturn(List.of(new CssApiService.CssUserDto(
            "abc@azureidir", "Jane", "Smith", "jane@gov.bc.ca", null)));

    assertThat(service.getUserRoleAssignments(INTEGRATION, ENV, DEFINER)).hasSize(1);
  }

  @Test
  @DisplayName("refuses to create a role on FAM's own integration")
  void refusesCreatingItsOwnRole() {
    assertThatThrownBy(() -> service.createRole(FAM_OWN_INTEGRATION, ENV,
        new CssRoleCreateRequest("SOMETHING", "Something", null, false, false),
        DEFINER))
        .isInstanceOf(FamHttpException.class);

    verify(cssApiService, never()).createRole(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("refuses to delete a role on FAM's own integration")
  void refusesDeletingItsOwnRole() {
    // The one that matters: deleting APP_ADMIN_22264_PROD would strip every
    // administrator of that application at once, and no screen would say so.
    assertThatThrownBy(() -> service.deleteRole(FAM_OWN_INTEGRATION, ENV,
        "APP_ADMIN_" + INTEGRATION + "_PROD", DEFINER))
        .isInstanceOf(FamHttpException.class);

    verify(cssApiService, never()).deleteRole(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("refuses the all-environments create on FAM's own integration too")
  void refusesCreatingItsOwnRoleEverywhere() {
    // Its own entry point, so guarding only the single-environment one would
    // leave the wider operation open.
    assertThatThrownBy(() -> service.createRoleInAllEnvironments(FAM_OWN_INTEGRATION,
        new CssRoleCreateRequest("SOMETHING", "Something", null, false, false),
        DEFINER))
        .isInstanceOf(FamHttpException.class);

    verify(cssApiService, never()).createRole(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("FAM's grantable roles exclude the ones administering other applications")
  void famRoleListExcludesAdminRoles() {
    // The Add permission screen offered APP_ADMIN_54321_DEV as though it were a
    // role of FAM's. Granting it there would have appointed an administrator of
    // FREP from the ordinary grant form, past every guard on the screen that
    // exists for it.
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, ENV)).thenReturn(List.of(
        role(FamAdminRole.FAM_ADMIN, false),
        role("APP_ADMIN_" + INTEGRATION + "_DEV", false),
        role("DELEGATED_ADMIN_" + INTEGRATION + "_DEV__CHR_FREP_EDITOR", false)));

    assertThat(service.getRoles(FAM_OWN_INTEGRATION, ENV))
        .extracting(CssRoleOptionDto::name)
        // FAM_ADMIN stays: granting it is how a FAM administrator is made.
        .containsExactly(FamAdminRole.FAM_ADMIN);
  }

  @Test
  @DisplayName("drops them before the per-composite fan-out, not after")
  void famRoleListDoesNotFanOutOverAdminRoles() {
    when(cssApiService.getRoles(FAM_OWN_INTEGRATION, ENV)).thenReturn(List.of(
        // Composite, so listing it would cost a request for its children.
        role("APP_ADMIN_" + INTEGRATION + "_DEV", true)));

    service.getRoles(FAM_OWN_INTEGRATION, ENV);

    verify(cssApiService, never()).getRoleComposites(anyInt(), anyString(), anyString());
  }

  @Test
  @DisplayName("another application's roles are untouched by the FAM rule")
  void otherApplicationsRoleListIsUnaffected() {
    givenRoles(
        role("FREP_ADMINISTRATOR", false),
        role("APP_ADMIN_" + INTEGRATION + "_DEV", false));

    // The rule is about FAM's integration, not about role names anywhere.
    assertThat(service.getRoles(INTEGRATION, ENV))
        .extracting(CssRoleOptionDto::name)
        .contains("APP_ADMIN_" + INTEGRATION + "_DEV");
  }
}
