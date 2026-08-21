package ca.bc.gov.nrs.fam.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
