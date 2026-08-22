package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.dto.ScopeDto;
import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.dto.CssIntegrationDto;
import ca.bc.gov.nrs.fam.dto.SelfApplicationRoleDto;
import ca.bc.gov.nrs.fam.dto.SelfPermissionDto;
import ca.bc.gov.nrs.fam.integration.CssApiService;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SelfPermissionService (port of admin_user_access_privilege)")
class SelfPermissionServiceTest {

  @Mock private CssApiService cssApiService;

  /**
   * Real config rather than a mock: the IDIR alias decides the CSS username this
   * looks a user up by, so it is worth exercising the actual resolution.
   */
  @org.mockito.Spy private ca.bc.gov.nrs.fam.configuration.FamProperties famProperties =
      new ca.bc.gov.nrs.fam.configuration.FamProperties(
          "dev", null,
          new ca.bc.gov.nrs.fam.configuration.FamProperties.Integration(null,
              new ca.bc.gov.nrs.fam.configuration.FamProperties.Integration.Css(
                  null, null, null, null, 12345,
                  new ca.bc.gov.nrs.fam.configuration.FamProperties.Integration.Css
                      .IdpAliases(null, null),
                  null),
              null, null));

  @InjectMocks private SelfPermissionService service;

  private static final String GUID = "AABBCCDDEEFF00112233445566778899";

  private static Requester requesterWith(String... roles) {
    return Requester.builder()
        .userName("JSMITH")
        .userGuid(GUID)
        .userType(ca.bc.gov.nrs.fam.constants.UserType.IDIR)
        .accessRoles(List.of(roles))
        .build();
  }

  /** The federated username CSS knows this requester by. */
  private static final String CSS_USERNAME = GUID.toLowerCase(java.util.Locale.ROOT) + "@azureidir";

  private static ca.bc.gov.nrs.fam.dto.CssRoleDto role(String name) {
    return new ca.bc.gov.nrs.fam.dto.CssRoleDto(name, false);
  }

  private void givenIntegrations() {
    when(cssApiService.getIntegrations()).thenReturn(List.of(
        new CssIntegrationDto(22264, "FREP", null, List.of("dev", "prod"), "applied", null, null),
        new CssIntegrationDto(54321, "FOM", null, List.of("dev"), "applied", null, null)));
  }

  @Test
  @DisplayName("names the application an APP_ADMIN role refers to")
  void namesTheApplication() {
    givenIntegrations();

    assertThat(service.getSelfPermissions(requesterWith("APP_ADMIN_22264_DEV")))
        .singleElement()
        .satisfies(permission -> {
          assertThat(permission.applicationName()).isEqualTo("FREP");
          assertThat(permission.environment()).isEqualTo("dev");
          assertThat(permission.cssIntegrationId()).isEqualTo(22264);
          assertThat(permission.role()).isEqualTo(AdminRoleAuthGroup.APP_ADMIN);
          assertThat(permission.roleDescription()).isEqualTo("Application administrator");
        });
  }

  @Test
  @DisplayName("distinguishes a delegated administrator from an application one")
  void distinguishesTiers() {
    givenIntegrations();

    assertThat(service.getSelfPermissions(
        requesterWith("APP_ADMIN_22264_DEV", "DELEGATED_ADMIN_54321_DEV")))
        .extracting(SelfPermissionDto::applicationName, SelfPermissionDto::role)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("FREP", AdminRoleAuthGroup.APP_ADMIN),
            org.assertj.core.groups.Tuple.tuple("FOM", AdminRoleAuthGroup.DELEGATED_ADMIN));
  }

  @Test
  @DisplayName("keeps environments apart - administering DEV is not administering PROD")
  void keepsEnvironmentsApart() {
    givenIntegrations();

    assertThat(service.getSelfPermissions(
        requesterWith("APP_ADMIN_22264_DEV", "APP_ADMIN_22264_PROD")))
        .extracting(SelfPermissionDto::environment)
        .containsExactly("dev", "prod");
  }

  @Test
  @DisplayName("FAM_ADMIN names no application and needs no CSS lookup")
  void famAdminNamesNoApplication() {
    assertThat(service.getSelfPermissions(requesterWith(FamAdminRole.FAM_ADMIN)))
        .singleElement()
        .satisfies(permission -> {
          assertThat(permission.applicationName()).isEqualTo("All applications");
          assertThat(permission.cssIntegrationId()).isNull();
          assertThat(permission.environment()).isNull();
          assertThat(permission.role()).isEqualTo(AdminRoleAuthGroup.FAM_ADMIN);
        });

    // Listing every integration would go stale the moment one is added.
    verify(cssApiService, never()).getIntegrations();
  }

  @Test
  @DisplayName("ignores roles that are not FAM's own")
  void ignoresApplicationRoles() {
    givenIntegrations();

    // An application's own role must never read as administrative authority.
    assertThat(service.getSelfPermissions(requesterWith("FREP_ADMINISTRATOR", "CHR_FREP_EDITOR")))
        .isEmpty();
  }

  @Test
  @DisplayName("still lists a role whose integration CSS no longer returns")
  void keepsUnresolvableIntegrations() {
    givenIntegrations();

    assertThat(service.getSelfPermissions(requesterWith("APP_ADMIN_99999_DEV")))
        .singleElement()
        .satisfies(permission -> {
          // The caller does hold it; hiding it would disagree with their token.
          assertThat(permission.applicationName()).isEqualTo("Integration 99999");
          assertThat(permission.cssIntegrationId()).isEqualTo(99999);
        });
  }

  @Test
  @DisplayName("degrades to ids rather than an empty screen when CSS is down")
  void survivesCssOutage() {
    when(cssApiService.getIntegrations()).thenThrow(new RuntimeException("CSS down"));

    assertThat(service.getSelfPermissions(requesterWith("APP_ADMIN_22264_DEV")))
        .singleElement()
        .satisfies(permission -> {
          assertThat(permission.applicationName()).isEqualTo("Integration 22264");
          assertThat(permission.role()).isEqualTo(AdminRoleAuthGroup.APP_ADMIN);
        });
  }

  @Test
  @DisplayName("skips a malformed role name rather than inventing an application")
  void skipsMalformedRoleNames() {
    givenIntegrations();

    assertThat(service.getSelfPermissions(
        requesterWith("APP_ADMIN_NOTANUMBER_DEV", "APP_ADMIN_", "APP_ADMIN_22264_")))
        .isEmpty();
  }

  @Test
  @DisplayName("returns nothing for a caller who administers nothing")
  void emptyForNonAdministrator() {
    assertThat(service.getSelfPermissions(requesterWith())).isEmpty();
    assertThat(service.getSelfPermissions(Requester.builder().userName("X").build())).isEmpty();
  }

  @Test
  @DisplayName("reads the application back out of the role name it was written into")
  void targetOfIsTheInverseOfTheBuilders() {
    // Round-trips the naming contract, so a change to one half breaks this.
    assertThat(FamAdminRole.targetOf(FamAdminRole.appAdmin(22264, "dev")))
        .get()
        .satisfies(target -> {
          assertThat(target.cssIntegrationId()).isEqualTo(22264);
          assertThat(target.cssEnvironment()).isEqualTo("dev");
        });

    assertThat(FamAdminRole.targetOf(FamAdminRole.delegatedAdmin(54321, "prod")))
        .get()
        .satisfies(target -> {
          assertThat(target.cssIntegrationId()).isEqualTo(54321);
          assertThat(target.cssEnvironment()).isEqualTo("prod");
        });

    assertThat(FamAdminRole.targetOf(FamAdminRole.FAM_ADMIN)).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Every application role the caller holds
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("looks the caller up by the CSS username built from their GUID")
  void looksUpByFederatedUsername() {
    givenIntegrations();

    service.getSelfApplicationRoles(requesterWith());

    // A wrong username silently returns nothing rather than failing, so the
    // exact value matters more than usual.
    verify(cssApiService).getUserRoles(22264, "dev", CSS_USERNAME);
    verify(cssApiService).getUserRoles(54321, "dev", CSS_USERNAME);
  }

  @Test
  @DisplayName("describes a held role from the application's sidecar")
  void describesHeldRoles() {
    givenIntegrations();
    when(cssApiService.getUserRoles(22264, "dev", CSS_USERNAME))
        .thenReturn(List.of(role("FREP_ADMINISTRATOR")));
    when(cssApiService.getRoles(22264, "dev")).thenReturn(List.of(
        role("FREP_ADMINISTRATOR"),
        role("FAM:LABEL:FREP_ADMINISTRATOR:FREP Administrator")));

    assertThat(service.getSelfApplicationRoles(requesterWith()))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.applicationName()).isEqualTo("FREP");
          assertThat(row.environment()).isEqualTo("dev");
          assertThat(row.roleName()).isEqualTo("FREP_ADMINISTRATOR");
          assertThat(row.roleDisplayName()).isEqualTo("FREP Administrator");
          assertThat(row.scopes()).isEmpty();
        });
  }

  @Test
  @DisplayName("recovers the scope from a scoped role's name")
  void recoversScope() {
    givenIntegrations();
    when(cssApiService.getUserRoles(22264, "dev", CSS_USERNAME))
        .thenReturn(List.of(
            role("CHR_FREP_EDITOR_DISTRICT-DCC"),
            role("CHR_FREP_EDITOR_DISTRICT-DKA")));
    when(cssApiService.getRoles(22264, "dev")).thenReturn(List.of(
        role("FAM:LABEL:CHR_FREP_EDITOR:Submitter (CHR)")));

    // Three districts is three roles, and three rows: that is what is held.
    assertThat(service.getSelfApplicationRoles(requesterWith()))
        .hasSize(2)
        .allSatisfy(row -> {
          assertThat(row.baseRoleName()).isEqualTo("CHR_FREP_EDITOR");
          assertThat(row.roleDisplayName()).isEqualTo("Submitter (CHR)");
          assertThat(row.scopes()).singleElement()
              .extracting(ScopeDto::type).isEqualTo("DISTRICT");
        })
        .flatExtracting(SelfApplicationRoleDto::scopes)
        .extracting(ScopeDto::value)
        .containsExactly("DCC", "DKA");
  }

  @Test
  @DisplayName("hides FAM's own bookkeeping roles")
  void hidesMarkersAndSidecars() {
    givenIntegrations();
    when(cssApiService.getUserRoles(22264, "dev", CSS_USERNAME))
        .thenReturn(List.of(
            role("FREP_ADMINISTRATOR"),
            role("HAS_DISTRICT_ROLE"),
            role("FAM:LABEL:FREP_ADMINISTRATOR:FREP Administrator")));
    when(cssApiService.getRoles(22264, "dev")).thenReturn(List.of());

    assertThat(service.getSelfApplicationRoles(requesterWith()))
        .extracting(SelfApplicationRoleDto::roleName)
        .containsExactly("FREP_ADMINISTRATOR");
  }

  @Test
  @DisplayName("spends no second request on an integration where nothing is held")
  void skipsEmptyIntegrations() {
    givenIntegrations();

    assertThat(service.getSelfApplicationRoles(requesterWith())).isEmpty();

    // Reading role descriptions is only worth it once something is held there.
    verify(cssApiService, never()).getRoles(anyInt(), anyString());
  }

  @Test
  @DisplayName("one unreachable integration does not cost the others their rows")
  void oneFailureDoesNotEmptyTheScreen() {
    givenIntegrations();
    when(cssApiService.getUserRoles(22264, "dev", CSS_USERNAME))
        .thenThrow(new RuntimeException("CSS down"));
    when(cssApiService.getUserRoles(54321, "dev", CSS_USERNAME))
        .thenReturn(List.of(role("FOM_SUBMITTER")));
    when(cssApiService.getRoles(54321, "dev")).thenReturn(List.of());

    assertThat(service.getSelfApplicationRoles(requesterWith()))
        .extracting(SelfApplicationRoleDto::roleName)
        .containsExactly("FOM_SUBMITTER");
  }

  @Test
  @DisplayName("returns nothing rather than guessing when the caller has no GUID")
  void noGuidNoLookup() {
    assertThat(service.getSelfApplicationRoles(
        Requester.builder().userName("JSMITH").build())).isEmpty();

    verify(cssApiService, never()).getUserRoles(anyInt(), anyString(), anyString());
  }
}
