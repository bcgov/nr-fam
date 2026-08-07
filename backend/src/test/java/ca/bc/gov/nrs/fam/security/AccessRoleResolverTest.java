package ca.bc.gov.nrs.fam.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.repository.FamApplicationAdminRepository;
import ca.bc.gov.nrs.fam.repository.FamApplicationRepository;
import ca.bc.gov.nrs.fam.repository.FamRoleRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Role resolution, which replaces the Cognito pre-token Lambda's group injection.
 *
 * <p>The security-relevant property is that application-admin authority is only
 * granted inside FAM's own console - it must never leak into a downstream
 * application's session.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccessRoleResolver (port of access_token_groups_override)")
class AccessRoleResolverTest {

  private static final String USER_GUID = "B5ECDB094DFB4149A6A8445A0MANGLED";
  private static final String IDIR = UserType.IDIR.getCode();

  @Mock private FamRoleRepository roleRepository;
  @Mock private FamApplicationAdminRepository applicationAdminRepository;
  @Mock private FamApplicationRepository applicationRepository;

  @InjectMocks private AccessRoleResolver resolver;

  private static FamApplication application(String name) {
    FamApplication application = new FamApplication();
    application.setApplicationId(1L);
    application.setApplicationName(name);
    return application;
  }

  @Test
  @DisplayName("returns the roles assigned within the calling application")
  void returnsAssignedRoles() {
    when(roleRepository.findRoleNamesForUserAndClient(USER_GUID, IDIR, "fom-client"))
        .thenReturn(List.of("FOM_REVIEWER", "FOM_SUBMITTER"));
    when(applicationRepository.findByOidcClientId("fom-client"))
        .thenReturn(Optional.of(application("FOM_DEV")));

    assertThat(resolver.resolveAccessRoles(USER_GUID, IDIR, "fom-client"))
        .containsExactly("FOM_REVIEWER", "FOM_SUBMITTER");
  }

  @Test
  @DisplayName("adds <APP>_ADMIN roles when signing in through FAM")
  void addsAdminRolesThroughFam() {
    when(roleRepository.findRoleNamesForUserAndClient(anyString(), anyString(), anyString()))
        .thenReturn(List.of());
    when(applicationRepository.findByOidcClientId("fam-console"))
        .thenReturn(Optional.of(application("FAM")));
    when(applicationAdminRepository.findAdministeredApplicationNames(USER_GUID, IDIR))
        .thenReturn(List.of("FOM_DEV", "SPAR_TEST"));

    assertThat(resolver.resolveAccessRoles(USER_GUID, IDIR, "fam-console"))
        .containsExactly("FOM_DEV_ADMIN", "SPAR_TEST_ADMIN");
  }

  @Test
  @DisplayName("does not grant admin authority through a downstream application's client")
  void doesNotLeakAdminRolesToOtherApplications() {
    // Signing in to FOM must not confer FAM administration, even for a user who
    // is a FAM app admin.
    when(roleRepository.findRoleNamesForUserAndClient(anyString(), anyString(), anyString()))
        .thenReturn(List.of("FOM_REVIEWER"));
    when(applicationRepository.findByOidcClientId("fom-client"))
        .thenReturn(Optional.of(application("FOM_DEV")));

    assertThat(resolver.resolveAccessRoles(USER_GUID, IDIR, "fom-client"))
        .containsExactly("FOM_REVIEWER");
    verify(applicationAdminRepository, never())
        .findAdministeredApplicationNames(anyString(), anyString());
  }

  @Test
  @DisplayName("upper-cases the application name in the admin role")
  void upperCasesAdminRoleName() {
    when(roleRepository.findRoleNamesForUserAndClient(anyString(), anyString(), anyString()))
        .thenReturn(List.of());
    when(applicationRepository.findByOidcClientId("fam-console"))
        .thenReturn(Optional.of(application("FAM")));
    when(applicationAdminRepository.findAdministeredApplicationNames(anyString(), anyString()))
        .thenReturn(List.of("fom_dev"));

    // Requester.isAdminOf matches on the upper-cased form.
    assertThat(resolver.resolveAccessRoles(USER_GUID, IDIR, "fam-console"))
        .containsExactly("FOM_DEV_ADMIN");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @DisplayName("resolves no roles when the token names no client")
  void noClientMeansNoRoles(String oidcClientId) {
    assertThat(resolver.resolveAccessRoles(USER_GUID, IDIR, oidcClientId)).isEmpty();
    verify(roleRepository, never())
        .findRoleNamesForUserAndClient(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("an unrecognised client yields no roles rather than an error")
  void unknownClientYieldsNoRoles() {
    when(roleRepository.findRoleNamesForUserAndClient(anyString(), anyString(), anyString()))
        .thenReturn(List.of());
    when(applicationRepository.findByOidcClientId(any())).thenReturn(Optional.empty());

    assertThat(resolver.resolveAccessRoles(USER_GUID, IDIR, "unknown-client")).isEmpty();
  }
}
