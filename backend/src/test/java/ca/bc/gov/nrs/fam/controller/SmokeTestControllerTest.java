package ca.bc.gov.nrs.fam.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.repository.FamUserTypeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * The post-deploy smoke check, which CI treats as a deployment gate.
 *
 * <p>It reads reference data rather than user data. Counting {@code fam_user}
 * meant a correctly migrated but freshly deployed environment answered 417 until
 * somebody signed in - failing the deployment for an empty user table, which is
 * the normal state of a new environment.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SmokeTestController (port of router_smoke_test.py)")
class SmokeTestControllerTest {

  @Mock private FamUserTypeRepository userTypeRepository;

  @InjectMocks private SmokeTestController controller;

  @Test
  @DisplayName("200 when the baseline's reference data is present")
  void okWhenMigrated() {
    // The five user type codes the baseline inserts.
    when(userTypeRepository.count()).thenReturn(5L);

    assertThat(controller.smokeTest().getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("200 even though no user has ever signed in")
  void okOnAFreshlyDeployedEnvironment() {
    // The case that used to fail: reachable, migrated, nobody has logged in yet.
    // Nothing here consults fam_user at all, which is the point.
    when(userTypeRepository.count()).thenReturn(5L);

    assertThat(controller.smokeTest().getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("417 when the database is reachable but not migrated")
  void expectationFailedWhenUnmigrated() {
    // Reference data is inserted by the migration that creates its table, so an
    // empty count means the baseline never ran - a failed deployment.
    when(userTypeRepository.count()).thenReturn(0L);

    assertThat(controller.smokeTest().getStatusCode())
        .isEqualTo(HttpStatus.EXPECTATION_FAILED);
  }
}
