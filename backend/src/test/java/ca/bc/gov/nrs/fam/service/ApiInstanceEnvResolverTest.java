package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.DirectoryEnv;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ApiInstanceEnvResolver (TEST vs PROD upstream instance)")
class ApiInstanceEnvResolverTest {

  private static ApiInstanceEnvResolver resolverFor(String deploymentEnvironment) {
    return new ApiInstanceEnvResolver(
        new FamProperties(deploymentEnvironment, null, null));
  }

  @Test
  @DisplayName("a prod application in a prod deployment reaches the PROD instance")
  void prodOnProdReachesProd() {
    assertThat(resolverFor("prod").resolve("prod")).isEqualTo(ApiInstanceEnv.PROD);
  }

  @ParameterizedTest(name = "deployment={0}, application={1}")
  @CsvSource({
      "dev,  prod",
      "test, prod",
      "prod, dev",
      "prod, test",
      "dev,  dev",
  })
  @DisplayName("anything short of prod-on-prod uses TEST")
  void anythingElseUsesTest(String deployment, String application) {
    // FAM's PROD deployment serves DEV, TEST and PROD applications. A DEV
    // application must never reach production data, so both sides must be prod.
    assertThat(resolverFor(deployment).resolve(application)).isEqualTo(ApiInstanceEnv.TEST);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  ", "production", "PRD", "staging"})
  @DisplayName("an unrecognised environment fails safe to TEST")
  void unrecognisedFailsSafeToTest(String environment) {
    // Guessing wrong towards PROD costs real data; guessing wrong towards TEST
    // costs a failed lookup.
    assertThat(resolverFor("prod").resolve(environment)).isEqualTo(ApiInstanceEnv.TEST);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PROD", "Prod", " prod "})
  @DisplayName("matches prod case-insensitively and ignores surrounding space")
  void matchesProdLoosely(String environment) {
    assertThat(resolverFor("prod").resolve(environment)).isEqualTo(ApiInstanceEnv.PROD);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @DisplayName("an unset deployment environment never reaches PROD")
  void unsetDeploymentNeverReachesProd(String deployment) {
    assertThat(resolverFor(deployment).resolve("prod")).isEqualTo(ApiInstanceEnv.TEST);
  }

  // ---------------------------------------------------------------------------
  // The identity directory, which is chosen on a different rule
  // ---------------------------------------------------------------------------

  @ParameterizedTest(name = "a {0} application is looked up in the {0} directory")
  @CsvSource({
      "dev,  DEV",
      "test, TEST",
      "prod, PROD",
  })
  @DisplayName("the directory follows the application, not the deployment")
  void directoryFollowsTheApplication(String application, DirectoryEnv expected) {
    /*
        Unlike resolve(), which asks whether PROD data may be touched at all.
        Here the environment is the answer rather than a permission: BCeID is
        deployed three times and a person is a different account with a
        different GUID in each, so a GUID read from the wrong instance is one
        CSS cannot resolve when it assigns the role - a refusal nobody sees.
    */
    assertThat(resolverFor("prod").resolveDirectory(application)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "a {0} deployment still administers test applications")
  @ValueSource(strings = {"dev", "test"})
  @DisplayName("a lower deployment reaches the lower directories")
  void lowerDeploymentReachesLowerDirectories(String deployment) {
    // The deployment does not narrow this the way it narrows resolve(): a dev
    // FAM administering a test application must look its users up in test, or
    // it would grant to somebody who does not exist there.
    assertThat(resolverFor(deployment).resolveDirectory("test"))
        .isEqualTo(DirectoryEnv.TEST);
    assertThat(resolverFor(deployment).resolveDirectory("dev"))
        .isEqualTo(DirectoryEnv.DEV);
  }

  @ParameterizedTest(name = "deployment={0}")
  @ValueSource(strings = {"dev", "test", "prod", ""})
  @DisplayName("the deployment does not narrow the directory, whatever it is")
  void deploymentDoesNotNarrowTheDirectory(String deployment) {
    /*
        Unlike resolve(), which gates PROD on the deployment.

        An earlier version refused prod from a lower deployment. That broke a
        legitimate selection: the lower environments run against their own
        sandbox integrations, and those carry a prod environment of their own -
        the prod environment of a sandbox application, not of a production one.
        Administering it is a normal thing to do from a dev laptop.

        What keeps a deployment out of the production directory is whether it
        holds that instance's credentials. An unconfigured instance says so by
        name when it is asked - see UserLookupClient - which is a true statement
        about configuration rather than an invented rule about authority.
    */
    assertThat(resolverFor(deployment).resolveDirectory("prod"))
        .isEqualTo(DirectoryEnv.PROD);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"staging", "PRODUCTION-ish"})
  @DisplayName("an unrecognised environment falls back to TEST, where it can do least harm")
  void unrecognisedFallsBackToTest(String application) {
    assertThat(resolverFor("prod").resolveDirectory(application))
        .isEqualTo(DirectoryEnv.TEST);
  }

  @Test
  @DisplayName("ignores case and surrounding space, as the CSS environment arrives")
  void directoryIgnoresCaseAndSpace() {
    assertThat(resolverFor("prod").resolveDirectory("  PROD ")).isEqualTo(DirectoryEnv.PROD);
    assertThat(resolverFor("dev").resolveDirectory(" Test ")).isEqualTo(DirectoryEnv.TEST);
  }
}
