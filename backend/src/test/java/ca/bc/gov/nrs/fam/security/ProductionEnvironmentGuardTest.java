package ca.bc.gov.nrs.fam.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import java.util.Map;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import java.util.TreeSet;
import java.util.Set;
import java.util.Locale;
import java.lang.reflect.Parameter;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/**
 * A non-prod FAM must not act on a production application environment.
 *
 * <p>The reason this matters is not obvious from the code it guards: every FAM
 * deployment talks to the same CSS, so a grant made from FAM TEST against a
 * {@code prod} environment is a real production grant - and it would be scoped
 * using Forest Client TEST data, because the API instance is chosen from the
 * deployment rather than the application.
 */
@DisplayName("ProductionEnvironmentGuard")
class ProductionEnvironmentGuardTest {

  private static ProductionEnvironmentGuard guardOn(String deploymentEnvironment) {
    return new ProductionEnvironmentGuard(
        new FamProperties(deploymentEnvironment, null, null));
  }

  private static MockHttpServletRequest withPathVariable(String name, String value) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/css-applications/1/" + value);
    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of(name, value));
    return request;
  }

  private static boolean run(ProductionEnvironmentGuard guard, MockHttpServletRequest request) {
    return guard.preHandle(request, new MockHttpServletResponse(), new Object());
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "test"})
  @DisplayName("refuses the prod environment from a non-prod deployment")
  void refusesProdFromLowerEnvironments(String deployment) {
    assertThatThrownBy(() -> run(guardOn(deployment), withPathVariable("environment", "prod")))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("production");
  }

  @ParameterizedTest
  @ValueSource(strings = {"PROD", "Prod", " prod "})
  @DisplayName("is not fooled by casing or padding")
  void normalisesTheValue(String value) {
    // The environment arrives from a URL, so it is whatever the caller typed.
    assertThatThrownBy(() -> run(guardOn("test"), withPathVariable("environment", value)))
        .isInstanceOf(FamHttpException.class);
  }

  @Test
  @DisplayName("catches the audit endpoint's differently-named query parameter")
  void catchesCssEnvironmentQueryParameter() {
    // Permission history takes cssEnvironment as a request parameter, not a path
    // variable. A guard that only knew one shape would leave it open.
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/permission-audit-history");
    request.setParameter("cssEnvironment", "prod");

    assertThatThrownBy(() -> run(guardOn("test"), request))
        .isInstanceOf(FamHttpException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "test"})
  @DisplayName("allows every non-prod environment")
  void allowsLowerEnvironments(String environment) {
    assertThat(run(guardOn("test"), withPathVariable("environment", environment))).isTrue();
  }

  @Test
  @DisplayName("the prod deployment may act on prod")
  void prodDeploymentIsUnrestricted() {
    assertThat(run(guardOn("prod"), withPathVariable("environment", "prod"))).isTrue();
  }

  @Test
  @DisplayName("knows every name an environment travels under across the controllers")
  void coversEveryEnvironmentParameterName() throws Exception {
    // The guard matches on parameter name, so an endpoint that calls it
    // something else would silently bypass it. Rather than trusting a list
    // written by hand, enumerate what the controllers actually declare.
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

    Set<String> declared = new TreeSet<>();
    for (BeanDefinition definition :
        scanner.findCandidateComponents("ca.bc.gov.nrs.fam.controller")) {
      for (Method method : Class.forName(definition.getBeanClassName()).getDeclaredMethods()) {
        for (Parameter parameter : method.getParameters()) {
          boolean bound = parameter.isAnnotationPresent(PathVariable.class)
              || parameter.isAnnotationPresent(RequestParam.class);
          if (bound && parameter.getName().toLowerCase(Locale.ROOT).contains("environment")) {
            declared.add(parameter.getName());
          }
        }
      }
    }

    // Guards the guard: if names were not compiled in, every check above would
    // vacuously pass.
    assertThat(declared)
        .as("controller parameter names must be compiled in (-parameters)")
        .isNotEmpty()
        .allSatisfy(name -> assertThat(name).doesNotStartWith("arg"));

    assertThat(declared)
        .as("names the guard must recognise")
        .isSubsetOf("environment", "cssEnvironment");
  }

  @Test
  @DisplayName("a request naming no environment passes through")
  void ignoresRequestsWithoutAnEnvironment() {
    // Reading one's own access names no environment; it must not be caught here.
    assertThatCode(() -> run(guardOn("test"), new MockHttpServletRequest("GET", "/auth/self")))
        .doesNotThrowAnyException();
  }
}
