package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.AppEnv;
import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Decides which instance of an external API a request should use.
 *
 * <p>Port of {@code crud_utils.use_api_instance_by_app}. FAM's PROD deployment
 * hosts DEV, TEST and PROD application records, but the Forest Client API and
 * IDIM proxy only publish TEST and PROD instances. Only a PROD application (or
 * FAM itself) running in FAM PROD reaches a PROD instance; everything else uses
 * TEST.
 *
 * <p>See the FAM wiki on Environment Management:
 * https://github.com/bcgov/nr-forests-access-management/wiki/Environment-Management
 *
 * <p>This fails safe. An unrecognised deployment environment, or an application
 * with no {@code app_environment}, resolves to TEST - so a misconfiguration sends
 * traffic to a test system rather than to production.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiInstanceEnvResolver {

  private static final String PROD_DEPLOYMENT = "prod";

  private final FamProperties famProperties;

  public ApiInstanceEnv resolve(FamApplication application) {
    if (!isProdDeployment()) {
      return ApiInstanceEnv.TEST;
    }

    boolean prodApplication = AppEnv.fromCode(application.getAppEnvironment())
        .filter(env -> env == AppEnv.PROD)
        .isPresent();
    boolean famItself =
        FamConstants.APPLICATION_FAM.equals(application.getApplicationName());

    ApiInstanceEnv resolved = (prodApplication || famItself)
        ? ApiInstanceEnv.PROD
        : ApiInstanceEnv.TEST;

    log.debug("Application {} ({}) resolved to API instance {}",
        application.getApplicationName(), application.getAppEnvironment(), resolved);
    return resolved;
  }

  private boolean isProdDeployment() {
    String deployment = famProperties.deploymentEnvironment();
    return deployment != null && PROD_DEPLOYMENT.equalsIgnoreCase(deployment.trim());
  }
}
