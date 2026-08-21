package ca.bc.gov.nrs.fam.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * Closes the hole left by {@code SchemaValidationIT} skipping itself when Docker
 * is missing.
 *
 * <p>That skip exists so a developer without Docker sees a skip rather than a
 * failure. On CI it would mean the only check that the entities match the real
 * schema quietly stopped running while the build reported success - the schema
 * could drift for weeks before a deployment noticed.
 *
 * <p>So: locally this test assumes its way out, and on CI it insists.
 */
@DisplayName("Docker is available on CI")
class DockerRequiredOnCiTest {

  @Test
  @DisplayName("CI must be able to run the schema validation")
  void dockerIsAvailableOnCi() {
    // GitHub Actions sets CI=true. Anywhere else, this is not our business.
    assumeTrue("true".equalsIgnoreCase(System.getenv("CI")), "not running on CI");

    assertThat(DockerClientFactory.instance().isDockerAvailable())
        .as("SchemaValidationIT skips without Docker, so CI without Docker "
            + "would leave the entities unvalidated against the real schema")
        .isTrue();
  }
}
