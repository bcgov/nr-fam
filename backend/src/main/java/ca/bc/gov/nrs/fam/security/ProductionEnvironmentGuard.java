package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Refuses to act on a {@code prod} CSS environment from a deployment that is not
 * itself production.
 *
 * <p>Every FAM deployment talks to the <em>same</em> CSS - {@code css_api_url} is
 * the production CSS API in all environments - so an integration's {@code prod}
 * environment is reachable from FAM DEV and FAM TEST as readily as from FAM PROD.
 * A grant made there is a real production grant. Nothing else in the application
 * compares the requested environment against the deployment's own: the admin
 * roles that authorise it ({@code APP_ADMIN_<id>_PROD}) are held per FAM
 * deployment, so granting one in FAM TEST is enough.
 *
 * <p>The second reason is quieter and was what surfaced this. External APIs are
 * chosen by {@link ca.bc.gov.nrs.fam.service.ApiInstanceEnvResolver} on the
 * <em>deployment</em>, so a non-prod deployment always reads the Forest Client
 * TEST instance. Administering a prod application from FAM TEST would therefore
 * scope a production role using test client numbers - numbers that need not
 * exist in production, or may belong to a different organisation there. This
 * guard is what makes that resolver rule safe rather than merely conservative,
 * and it is why a non-prod deployment needs no PROD Forest Client credentials.
 *
 * <p>Implemented as an interceptor rather than a call in each controller because
 * it has to hold for <em>every</em> endpoint that names an environment - there
 * are seventeen today - and an endpoint added later must not be able to opt out
 * by forgetting it.
 *
 * <p>Reads of a person's own access are deliberately untouched: they name no
 * environment, deriving what they show from the caller's own roles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductionEnvironmentGuard implements HandlerInterceptor {

  private static final String PROD = "prod";

  /** The names an environment travels under, as a path variable or a parameter. */
  private static final String[] PARAM_NAMES = {"environment", "cssEnvironment"};

  private final FamProperties famProperties;

  @Override
  @SuppressWarnings("unchecked")
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {

    if (isProd(famProperties.deploymentEnvironment())) {
      return true;
    }

    Map<String, String> pathVariables = (Map<String, String>)
        request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);

    for (String name : PARAM_NAMES) {
      if (pathVariables != null && isProd(pathVariables.get(name))) {
        throw refuse(request, name);
      }
      if (isProd(request.getParameter(name))) {
        throw refuse(request, name);
      }
    }
    return true;
  }

  private FamHttpException refuse(HttpServletRequest request, String name) {
    log.warn("Refusing {} {}: a {} deployment may not act on the prod environment ({}).",
        request.getMethod(), request.getRequestURI(),
        famProperties.deploymentEnvironment(), name);

    return FamHttpException.forbidden(ErrorCode.PERMISSION_REQUIRED,
        "This is not the production deployment of FAM, so it cannot act on the "
            + "production environment of an application. Use production FAM.");
  }

  private static boolean isProd(String value) {
    return value != null && PROD.equalsIgnoreCase(value.trim().toLowerCase(Locale.ROOT));
  }
}
