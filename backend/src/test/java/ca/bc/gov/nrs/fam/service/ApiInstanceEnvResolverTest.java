package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rule that decides whether a request may reach a production external API.
 *
 * <p>Getting this wrong sends test traffic at production systems, so every branch
 * is covered, including the fail-safe ones.
 */
@DisplayName("ApiInstanceEnvResolver (port of use_api_instance_by_app)")
class ApiInstanceEnvResolverTest {

  private static ApiInstanceEnvResolver resolver(String deploymentEnvironment) {
    return new ApiInstanceEnvResolver(
        new FamProperties(deploymentEnvironment, null, null, null));
  }

  private static FamApplication application(String name, String appEnvironment) {
    FamApplication application = new FamApplication();
    application.setApplicationId(1L);
    application.setApplicationName(name);
    application.setAppEnvironment(appEnvironment);
    return application;
  }

  @ParameterizedTest(name = "FAM prod + {0}/{1} -> {2}")
  @CsvSource({
      // Only a PROD application in FAM PROD reaches the PROD instance.
      "FOM_PROD, PROD, PROD",
      "FOM_TEST, TEST, TEST",
      "FOM_DEV,  DEV,  TEST",
      // FAM's own row has no environment but is treated as production.
      "FAM,      ,     PROD",
  })
  @DisplayName("in a prod deployment, only prod applications and FAM itself use the prod instance")
  void resolvesInProdDeployment(String name, String appEnvironment, ApiInstanceEnv expected) {
    assertThat(resolver("prod").resolve(application(name, appEnvironment))).isEqualTo(expected);
  }

  @ParameterizedTest(name = "deployment={0}")
  @ValueSource(strings = {"dev", "test"})
  @DisplayName("non-prod deployments always use the test instance, even for a prod application")
  void nonProdDeploymentAlwaysUsesTest(String deploymentEnvironment) {
    assertThat(resolver(deploymentEnvironment).resolve(application("FOM_PROD", "PROD")))
        .isEqualTo(ApiInstanceEnv.TEST);
    assertThat(resolver(deploymentEnvironment).resolve(application("FAM", null)))
        .isEqualTo(ApiInstanceEnv.TEST);
  }

  @Test
  @DisplayName("accepts a differently-cased deployment value")
  void deploymentValueIsCaseInsensitive() {
    assertThat(resolver("PROD").resolve(application("FOM_PROD", "PROD")))
        .isEqualTo(ApiInstanceEnv.PROD);
  }

  @ParameterizedTest(name = "deployment={0}")
  @ValueSource(strings = {"", "  ", "production", "staging"})
  @DisplayName("fails safe to test when the deployment environment is not recognised")
  void unrecognisedDeploymentFailsSafe(String deploymentEnvironment) {
    assertThat(resolver(deploymentEnvironment).resolve(application("FOM_PROD", "PROD")))
        .isEqualTo(ApiInstanceEnv.TEST);
  }

  @Test
  @DisplayName("fails safe to test when the deployment environment is unset")
  void nullDeploymentFailsSafe() {
    assertThat(resolver(null).resolve(application("FOM_PROD", "PROD")))
        .isEqualTo(ApiInstanceEnv.TEST);
  }

  @Test
  @DisplayName("fails safe to test for an application with an unrecognised environment")
  void unrecognisedAppEnvironmentFailsSafe() {
    assertThat(resolver("prod").resolve(application("FOM_QA", "QA")))
        .isEqualTo(ApiInstanceEnv.TEST);
  }
}
