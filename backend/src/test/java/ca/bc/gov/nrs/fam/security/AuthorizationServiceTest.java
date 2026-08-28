package ca.bc.gov.nrs.fam.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

/**
 * The guards that survived V94.
 *
 * <p>Everything that took a {@code FamRole} or a FAM application id went with the
 * tables behind it; what remains is decided entirely from the requester's token.
 */
@DisplayName("AuthorizationService (token-derived guards)")
class AuthorizationServiceTest {

  private final AuthorizationService service = new AuthorizationService(famPropertiesWithOwnIntegration());


  /** FAM's own integration, reserved to FAM_ADMIN. */
  static final int FAM_OWN_INTEGRATION = 12345;

  private static ca.bc.gov.nrs.fam.configuration.FamProperties
      famPropertiesWithOwnIntegration() {
    return new ca.bc.gov.nrs.fam.configuration.FamProperties("dev", null,
        new ca.bc.gov.nrs.fam.configuration.FamProperties.Integration(null,
            new ca.bc.gov.nrs.fam.configuration.FamProperties.Integration.Css(
                null, null, null, null, FAM_OWN_INTEGRATION,
                new ca.bc.gov.nrs.fam.configuration.FamProperties.Integration.Css
                    .IdpAliases(null, null),
                null),
            null, null));
  }

  private static Requester withRoles(String... roles) {
    return Requester.builder()
        .userGuid("AAAA")
        .userType(UserType.IDIR)
        .accessRoles(List.of(roles))
        .build();
  }

  @Test
  @DisplayName("allows a caller holding any admin role")
  void allowsCallerWithAnyRole() {
    assertThatCode(() -> service.authorize(withRoles("FOM_DEV_ADMIN")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses a caller with no roles at all")
  void refusesCallerWithNoRoles() {
    // Admin rights are token roles now, so an empty list means they administer
    // nothing - there is no second source to fall back on.
    assertThatThrownBy(() -> service.authorize(withRoles()))
        .isInstanceOf(FamHttpException.class)
        .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("refuses a caller whose roles are null")
  void refusesNullRoles() {
    assertThatThrownBy(() -> service.authorize(Requester.builder().build()))
        .isInstanceOf(FamHttpException.class);
  }

  @Test
  @DisplayName("FAM administration requires the FAM_ADMIN role specifically")
  void famAdminRequiresFamRole() {
    assertThatCode(() -> service.authorizeByFamAdmin(withRoles("FAM_ADMIN")))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> service.authorizeByFamAdmin(withRoles("FOM_DEV_ADMIN")))
        .isInstanceOf(FamHttpException.class);
  }

  @ParameterizedTest
  @EnumSource(value = UserType.class, names = {"IDIR"}, mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("IDIR-only endpoints refuse every other user type")
  void internalOnlyRefusesExternalUsers(UserType userType) {
    Requester external = Requester.builder()
        .userType(userType).accessRoles(List.of("X_ADMIN")).build();

    assertThatThrownBy(() -> service.internalOnlyAction(external))
        .isInstanceOf(FamHttpException.class);
  }

  @Test
  @DisplayName("IDIR-only endpoints allow an IDIR caller")
  void internalOnlyAllowsIdir() {
    assertThatCode(() -> service.internalOnlyAction(withRoles("X_ADMIN")))
        .doesNotThrowAnyException();
  }

  private static Requester bceidRequester(String businessGuid) {
    return Requester.builder()
        .userType(UserType.BCEID).businessGuid(businessGuid)
        .accessRoles(List.of("X_ADMIN")).build();
  }

  @Test
  @DisplayName("lets a BCeID caller read a user from their own organisation")
  void allowsSameOrganization() {
    assertThatCode(() -> service.enforceSameOrganization(bceidRequester("ORG-A"), "ORG-A"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses a BCeID caller reading a user from another organisation")
  void refusesOtherOrganization() {
    // This rule used to live inside the IDIM integration. nr-user-lookup-api
    // takes no requester, so losing it would let a BCeID administrator enumerate
    // users at other organisations.
    assertThatThrownBy(() -> service.enforceSameOrganization(bceidRequester("ORG-A"), "ORG-B"))
        .isInstanceOf(FamHttpException.class)
        .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("matches organisations case-insensitively")
  void sameOrganizationIgnoresCasing() {
    assertThatCode(() -> service.enforceSameOrganization(bceidRequester("org-a"), "ORG-A"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses when either organisation is unknown, rather than allowing it")
  void unknownOrganizationIsRefused() {
    // An absent organisation is not a matching one. Allowing it would turn a
    // missing attribute into unrestricted access.
    assertThatThrownBy(() -> service.enforceSameOrganization(bceidRequester(null), "ORG-A"))
        .isInstanceOf(FamHttpException.class);
    assertThatThrownBy(() -> service.enforceSameOrganization(bceidRequester("ORG-A"), null))
        .isInstanceOf(FamHttpException.class);
  }

  @Test
  @DisplayName("does not restrict an IDIR caller")
  void idirCallerIsUnrestricted() {
    // The rule is about BCeID administrators being confined to their own
    // business; IDIR staff administer across organisations by design.
    assertThatCode(() -> service.enforceSameOrganization(withRoles("X_ADMIN"), "ANY-ORG"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses a grant the requester is making to themselves")
  void refusesSelfGrant() {
    // Unconditional: the old exemption for app admins on DEV/TEST of another
    // application depended on FAM knowing application environments from its own
    // tables, which it no longer does.
    assertThatThrownBy(() -> service.forbidSelfGrant(withRoles("X_ADMIN"), "aaaa"))
        .isInstanceOf(FamHttpException.class);
  }

  @Test
  @DisplayName("allows a grant to somebody else")
  void allowsGrantToAnotherUser() {
    assertThatCode(() -> service.forbidSelfGrant(withRoles("X_ADMIN"), "BBBB"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a requester with no GUID cannot accidentally match a target")
  void nullRequesterGuidDoesNotMatch() {
    Requester noGuid = Requester.builder().accessRoles(List.of("X_ADMIN")).build();

    assertThatCode(() -> service.forbidSelfGrant(noGuid, null))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("self-grant comparison ignores GUID casing")
  void selfGrantIgnoresCasing() {
    // FAM stores GUIDs upper case; CSS usernames are lower case.
    assertThat(
        assertThatThrownBy(() -> service.forbidSelfGrant(withRoles("X_ADMIN"), "AaAa"))
            .isInstanceOf(FamHttpException.class)).isNotNull();
  }

  // ---------------------------------------------------------------------------
  // Managing an application's roles
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("a DevOps administrator may manage the roles of their own application")
  void devopsAdminManagesItsOwnRoles() {
    assertThatCode(() -> service.requireRoleManagement(
        withRoles(FamAdminRole.devopsAdmin(22264, "DEV")), 22264, "DEV"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("and no other application, and no other environment")
  void devopsAdminIsPennedIn() {
    Requester devops = withRoles(FamAdminRole.devopsAdmin(22264, "DEV"));

    assertThatThrownBy(() -> service.requireRoleManagement(devops, 99999, "DEV"))
        .isInstanceOf(FamHttpException.class);
    // A role invented in DEV is not a role invented in PROD.
    assertThatThrownBy(() -> service.requireRoleManagement(devops, 22264, "PROD"))
        .isInstanceOf(FamHttpException.class);
  }

  @Test
  @DisplayName("an application administrator may not manage roles")
  void appAdminMayNotManageRoles() {
    /*
        The rule that was already in force when this was FAM administrators
        only: they hand out what the application defines without also being able
        to invent something new for it to mean.
    */
    assertThatThrownBy(() -> service.requireRoleManagement(
        withRoles(FamAdminRole.appAdmin(22264, "DEV")), 22264, "DEV"))
        .isInstanceOf(FamHttpException.class);
  }

  @Test
  @DisplayName("a FAM administrator manages any application's roles")
  void famAdminManagesEverything() {
    assertThatCode(() -> service.requireRoleManagement(
        withRoles(FamAdminRole.FAM_ADMIN), 99999, "PROD"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("managing DevOps administrators is FAM administrators only")
  void onlyFamAdminsManageDevopsAdmins() {
    // Not even a DevOps administrator of that application: holding the tier is
    // not authority to hand it out.
    assertThatCode(() ->
        service.requireDevopsAdminManagement(withRoles(FamAdminRole.FAM_ADMIN)))
        .doesNotThrowAnyException();

    for (Requester other : List.of(
        withRoles(FamAdminRole.appAdmin(22264, "DEV")),
        withRoles(FamAdminRole.devopsAdmin(22264, "DEV")),
        withRoles())) {
      assertThatThrownBy(() -> service.requireDevopsAdminManagement(other))
          .isInstanceOf(FamHttpException.class);
    }
  }

  @Test
  @DisplayName("a DevOps administrator is given no access management at all")
  void devopsAdminGrantsNoAccess() {
    /*
        The whole point of keeping this tier off Requester.tierFor. If it were a
        rung on that ladder, everything that grants and revokes would read it as
        authority over access - which is the one thing it is not for.
    */
    Requester devops = withRoles(FamAdminRole.devopsAdmin(22264, "DEV"));

    assertThat(devops.canManageAccess(22264, "DEV")).isFalse();
    assertThat(devops.tierFor(22264, "DEV")).isEmpty();
    assertThat(devops.canManageDelegatedAdmins(22264, "DEV")).isFalse();
    assertThat(devops.canGrantRole(22264, "DEV", "ANY_ROLE")).isFalse();
    // But it does manage roles, which is what it is for.
    assertThat(devops.canManageRoles(22264, "DEV")).isTrue();
  }

  @Test
  @DisplayName("says what the rule is and what to do about it")
  void selfChangeIsExplained() {
    /*
        The guard is reached from granting, revoking, appointing and removing, so
        it cannot be worded as though the caller were only ever adding something -
        and a refusal that only says no leaves somebody clicking the same button
        again.
    */
    Requester self = Requester.builder()
        .userGuid("AAAA").userType(UserType.IDIR).accessRoles(List.of()).build();

    assertThatThrownBy(() -> service.forbidSelfGrant(self, "aaaa"))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("your own permissions")
        .hasMessageContaining("another administrator");
  }

  @Test
  @DisplayName("a DevOps administrator may read the roles of their own application")
  void devopsAdminMayReadTheRoles() {
    /*
        The Manage roles screen they were given could not draw its own table:
        the listing was gated on administering access, which they do not.
    */
    assertThatCode(() -> service.requireRoleVisibility(
        withRoles(FamAdminRole.devopsAdmin(22264, "DEV")), 22264, "DEV"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("and not of an application they were not appointed for")
  void devopsAdminReadsOnlyTheirOwn() {
    assertThatThrownBy(() -> service.requireRoleVisibility(
        withRoles(FamAdminRole.devopsAdmin(22264, "DEV")), 99999, "DEV"))
        .isInstanceOf(FamHttpException.class);
  }

  @Test
  @DisplayName("everyone who administers the application may still read them")
  void everyTierMayStillRead() {
    // Granting a role means choosing from the same listing.
    for (String role : List.of(
        FamAdminRole.appAdmin(22264, "DEV"),
        FamAdminRole.delegatedAdmin(22264, "DEV"),
        FamAdminRole.FAM_ADMIN)) {
      assertThatCode(() ->
          service.requireRoleVisibility(withRoles(role), 22264, "DEV"))
          .doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("reading is all it admits")
  void readingIsAllItAdmits() {
    // Creating and deleting stay on requireRoleManagement; granting stays on
    // requireApplicationAccess. Neither is opened by being able to look.
    Requester devops = withRoles(FamAdminRole.devopsAdmin(22264, "DEV"));

    assertThatThrownBy(() -> service.requireApplicationAccess(devops, 22264, "DEV"))
        .isInstanceOf(FamHttpException.class);
  }
}
