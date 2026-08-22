package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Decides whether an outbound integration talks to its TEST or PROD instance.
 *
 * <p>Port of {@code use_api_instance_by_app}, re-expressed for CSS. FAM used to
 * read the environment off the {@code fam_application} row; applications live in
 * CSS now, so the caller supplies the CSS environment instead.
 *
 * <p>The rule is deliberately conservative: <strong>both</strong> the deployment
 * and the target application must be production before a PROD instance is used.
 * FAM's PROD deployment serves DEV, TEST and PROD applications, and a DEV
 * application must never reach production data.
 *
 * <p><b>The deployment half is still load-bearing.</b> The lower environments run
 * against their own set of CSS integrations, and those integrations have a
 * {@code prod} environment of their own - it is the prod environment of a test
 * application, not of a production one. Without the deployment check, selecting
 * it would ask for the Forest Client PROD instance, which a lower deployment has
 * no credentials for; the request would fail rather than quietly return test
 * data, but failing is not the right answer to a legitimate selection.
 *
 * <p>Anything unrecognised resolves to TEST. Failing safe matters more here than
 * being strict: the cost of guessing wrong towards PROD is real data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiInstanceEnvResolver {

  private static final String PROD = "prod";

  private final FamProperties famProperties;

  /**
   * @param cssEnvironment the CSS integration's environment, e.g. {@code dev}.
   *     Null or unrecognised yields TEST.
   */
  public ApiInstanceEnv resolve(String cssEnvironment) {
    if (!isProd(famProperties.deploymentEnvironment())) {
      return ApiInstanceEnv.TEST;
    }
    return isProd(cssEnvironment) ? ApiInstanceEnv.PROD : ApiInstanceEnv.TEST;
  }

  private static boolean isProd(String value) {
    return value != null && PROD.equalsIgnoreCase(value.trim().toLowerCase(Locale.ROOT));
  }
}
