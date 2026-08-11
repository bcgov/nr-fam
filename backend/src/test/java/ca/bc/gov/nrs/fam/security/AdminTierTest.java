package ca.bc.gov.nrs.fam.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FAM's three administrative tiers.
 *
 * <p>The distinction that matters is the last one: a delegated administrator may
 * grant ordinary access but may not appoint administrators. Without that, the
 * tier is decorative - anyone holding it could promote themselves.
 */
@DisplayName("Administrative tiers")
class AdminTierTest {

  private static final int APP = 22264;
  private static final int OTHER_APP = 22265;
  private static final String DEV = "dev";

  private final AuthorizationService service = new AuthorizationService(famPropertiesWithOwnIntegration());


  /** FAM's own integration, reserved to FAM_ADMIN. */
  static final int FAM_OWN_INTEGRATION = 22261;

  private static ca.bc.gov.nrs.fam.configuration.FamProperties
      famPropertiesWithOwnIntegration() {
    return new ca.bc.gov.nrs.fam.configuration.FamProperties("dev", null,
        new ca.bc.gov.nrs.fam.configuration.FamProperties.Integration(null,
            new ca.bc.gov.nrs.fam.configuration.FamProperties.Integration.Css(
                null, null, null, null, FAM_OWN_INTEGRATION,
                new ca.bc.gov.nrs.fam.configuration.FamProperties.Integration.Css
                    .IdpAliases(null, null),
                null),
            null, null),
        null);
  }

  private static Requester with(String... roles) {
    return Requester.builder().userGuid("AAAA").accessRoles(List.of(roles)).build();
  }

  @Nested
  @DisplayName("role names")
  class RoleNames {

    @Test
    @DisplayName("name the application by integration id and environment")
    void namesTheApplication() {
      assertThat(FamAdminRole.appAdmin(APP, DEV)).isEqualTo("APP_ADMIN_22264_DEV");
      assertThat(FamAdminRole.delegatedAdmin(APP, DEV))
          .isEqualTo("DELEGATED_ADMIN_22264_DEV");
    }

    @Test
    @DisplayName("read the tier back off a role name")
    void readsTheTierBack() {
      assertThat(FamAdminRole.tierOf("FAM_ADMIN")).contains(AdminRoleAuthGroup.FAM_ADMIN);
      assertThat(FamAdminRole.tierOf("APP_ADMIN_22264_DEV"))
          .contains(AdminRoleAuthGroup.APP_ADMIN);
      assertThat(FamAdminRole.tierOf("DELEGATED_ADMIN_22264_DEV"))
          .contains(AdminRoleAuthGroup.DELEGATED_ADMIN);
    }

    @Test
    @DisplayName("do not mistake an application's own role for administrative authority")
    void applicationRolesAreNotAdminRoles() {
      // These pass through the same grant path; treating one as a tier would let
      // an application define its way into administering FAM.
      assertThat(FamAdminRole.tierOf("FREP_ADMINISTRATOR")).isEmpty();
      assertThat(FamAdminRole.tierOf("CHR_FREP_EDITOR_DISTRICT-DCC")).isEmpty();
      assertThat(FamAdminRole.tierOf("SUBMITTER")).isEmpty();
      assertThat(FamAdminRole.tierOf(null)).isEmpty();
    }
  }

  @Nested
  @DisplayName("FAM_ADMIN")
  class FamAdmin {

    @Test
    @DisplayName("administers every application without holding a per-application role")
    void administersEverything() {
      Requester famAdmin = with(FamAdminRole.FAM_ADMIN);

      assertThat(famAdmin.canManageAccess(APP, DEV)).isTrue();
      assertThat(famAdmin.canManageAccess(OTHER_APP, "prod")).isTrue();
      assertThat(famAdmin.canManageDelegatedAdmins(APP, DEV)).isTrue();
    }

    @Test
    @DisplayName("reports as the highest tier, so callers need no special case")
    void reportsHighestTier() {
      assertThat(with(FamAdminRole.FAM_ADMIN).tierFor(APP, DEV))
          .contains(AdminRoleAuthGroup.FAM_ADMIN);
    }
  }

  @Nested
  @DisplayName("APP_ADMIN")
  class AppAdmin {

    private final Requester appAdmin = with(FamAdminRole.appAdmin(APP, DEV));

    @Test
    @DisplayName("manages access and appoints delegated admins for its own application")
    void managesItsOwnApplication() {
      assertThat(appAdmin.canManageAccess(APP, DEV)).isTrue();
      assertThat(appAdmin.canManageDelegatedAdmins(APP, DEV)).isTrue();
    }

    @Test
    @DisplayName("has no authority over another application")
    void hasNoAuthorityElsewhere() {
      assertThat(appAdmin.canManageAccess(OTHER_APP, DEV)).isFalse();
      assertThatThrownBy(() -> service.requireApplicationAccess(appAdmin, OTHER_APP, DEV))
          .isInstanceOf(FamHttpException.class);
    }

    @Test
    @DisplayName("has no authority over another environment of the same application")
    void hasNoAuthorityInAnotherEnvironment() {
      // Environment is part of the role name precisely so administering DEV does
      // not imply administering PROD.
      assertThat(appAdmin.canManageAccess(APP, "prod")).isFalse();
    }
  }

  @Nested
  @DisplayName("DELEGATED_ADMIN")
  class DelegatedAdmin {

    private final Requester delegated = with(FamAdminRole.delegatedAdmin(APP, DEV));

    @Test
    @DisplayName("grants and revokes ordinary access")
    void managesAccess() {
      assertThat(delegated.canManageAccess(APP, DEV)).isTrue();
      assertThatCode(() -> service.requireApplicationAccess(delegated, APP, DEV))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cannot appoint delegated administrators")
    void cannotAppointAdministrators() {
      // The whole distinction between this tier and APP_ADMIN. Allowing it would
      // let a delegated admin promote themselves.
      assertThat(delegated.canManageDelegatedAdmins(APP, DEV)).isFalse();

      assertThatThrownBy(() -> service.requireDelegatedAdminManagement(delegated, APP, DEV))
          .isInstanceOf(FamHttpException.class)
          .hasMessageContaining("delegated administrators");
    }

    @Test
    @DisplayName("cannot appoint application administrators either")
    void cannotAppointAppAdmins() {
      assertThat(delegated.canManageDelegatedAdmins(APP, DEV)).isFalse();
    }
  }

  @Nested
  @DisplayName("no tier")
  class NoTier {

    @Test
    @DisplayName("a role for one application grants nothing on another")
    void unrelatedRoleGrantsNothing() {
      Requester elsewhere = with(FamAdminRole.appAdmin(OTHER_APP, DEV));

      assertThat(elsewhere.tierFor(APP, DEV)).isEmpty();
      assertThatThrownBy(() -> service.requireApplicationAccess(elsewhere, APP, DEV))
          .isInstanceOf(FamHttpException.class);
    }

    @Test
    @DisplayName("an application's own role grants no administrative authority")
    void applicationRoleGrantsNothing() {
      Requester holder = with("FREP_ADMINISTRATOR");

      assertThat(holder.tierFor(APP, DEV)).isEmpty();
    }

    @Test
    @DisplayName("matches role names case-insensitively")
    void matchesCaseInsensitively() {
      // CSS role names are free text; casing is easy to get wrong when creating
      // them by hand.
      assertThat(with("app_admin_22264_dev").canManageAccess(APP, DEV)).isTrue();
    }
  }

  @Nested
  @DisplayName("FAM's own integration")
  class OwnIntegration {

    @Test
    @DisplayName("is administrable by FAM_ADMIN")
    void famAdminMayAdministerIt() {
      assertThat(service.canAdminister(
          with(FamAdminRole.FAM_ADMIN), FAM_OWN_INTEGRATION, DEV)).isTrue();
    }

    @Test
    @DisplayName("is hidden from an APP_ADMIN holding a role for it")
    void appAdminMayNotAdministerIt() {
      // Administering FAM is deciding who administers everything else, so the
      // per-application role is not enough - even when someone has created it.
      Requester appAdmin = with(FamAdminRole.appAdmin(FAM_OWN_INTEGRATION, DEV));

      assertThat(service.canAdminister(appAdmin, FAM_OWN_INTEGRATION, DEV)).isFalse();
      assertThatThrownBy(() ->
          service.requireApplicationAccess(appAdmin, FAM_OWN_INTEGRATION, DEV))
          .isInstanceOf(FamHttpException.class);
    }

    @Test
    @DisplayName("is hidden from a DELEGATED_ADMIN holding a role for it")
    void delegatedAdminMayNotAdministerIt() {
      Requester delegated = with(FamAdminRole.delegatedAdmin(FAM_OWN_INTEGRATION, DEV));

      assertThat(service.canAdminister(delegated, FAM_OWN_INTEGRATION, DEV)).isFalse();
    }

    @Test
    @DisplayName("cannot be reached by calling the endpoints directly")
    void directAccessIsRefused() {
      // The picker filters it out, but hiding it in the response is not the
      // control - a caller who knows the integration id can call the roles,
      // assignment and grant endpoints regardless. The guard is what stops them.
      Requester appAdmin = with(FamAdminRole.appAdmin(FAM_OWN_INTEGRATION, DEV));

      assertThatThrownBy(() ->
          service.requireApplicationAccess(appAdmin, FAM_OWN_INTEGRATION, DEV))
          .isInstanceOf(FamHttpException.class);
      assertThatThrownBy(() ->
          service.requireDelegatedAdminManagement(appAdmin, FAM_OWN_INTEGRATION, DEV))
          .isInstanceOf(FamHttpException.class);
    }

    @Test
    @DisplayName("does not say which integration is FAM's own when refusing")
    void refusalDoesNotIdentifyFam() {
      // The same message either way. Naming FAM's integration in the error would
      // confirm it to a caller who was guessing ids.
      Requester appAdmin = with(FamAdminRole.appAdmin(FAM_OWN_INTEGRATION, DEV));
      Requester elsewhere = with(FamAdminRole.appAdmin(OTHER_APP, DEV));

      String onOwn = catchThrowable(() ->
          service.requireApplicationAccess(appAdmin, FAM_OWN_INTEGRATION, DEV)).getMessage();
      String onOther = catchThrowable(() ->
          service.requireApplicationAccess(elsewhere, APP, DEV)).getMessage();

      assertThat(onOwn).isEqualTo(onOther);
    }

    @Test
    @DisplayName("other applications are unaffected")
    void otherApplicationsUnaffected() {
      assertThat(service.canAdminister(
          with(FamAdminRole.appAdmin(APP, DEV)), APP, DEV)).isTrue();
    }
  }
}
