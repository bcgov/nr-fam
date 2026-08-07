package ca.bc.gov.nrs.fam.configuration;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of the {@code fam.*} configuration in {@code application.yml}.
 *
 * <p>Replaces {@code server/backend/api/config/config.py}, which read environment
 * variables directly and raised {@code MissingEnvironmentVariable} at call time.
 * Binding them here means a misconfigured environment fails at startup instead.
 */
@ConfigurationProperties(prefix = "fam")
public record FamProperties(
    /**
     * Which FAM deployment this is: {@code dev}, {@code test} or {@code prod}.
     *
     * <p>Replaces the {@code TARGET_ENV} variable the AWS platform injected. It
     * decides whether an external API's PROD instance may be used at all - see
     * {@code ApiInstanceEnvResolver}.
     */
    String deploymentEnvironment,
    Cors cors,
    Integration integration,
    UpdateUserInfo updateUserInfo) {

  public record Cors(List<String> allowedOrigins) {}

  public record Integration(ForestClient forestClient, IdimProxy idimProxy, GcNotify gcNotify) {

    /**
     * FAM's PROD environment serves DEV, TEST and PROD applications, but external
     * APIs only publish TEST and PROD instances. Only a PROD application in FAM
     * PROD talks to a PROD instance; everything else uses TEST.
     */
    public record ForestClient(Instance test, Instance prod, Timeouts timeouts, Retry retry) {

      public record Instance(String baseUrl, String apiToken) {}

      /**
       * Retry policy for the Forest Client API.
       *
       * <p>Sized against the 29-second API Gateway ceiling upstream ran under: two
       * attempts at 5s connect + 10s read, plus a 2s delay between them, stays
       * inside it. That ceiling no longer applies on OpenShift, but the budget is
       * kept so behaviour under a slow upstream is unchanged.
       */
      public record Retry(int maxAttempts, Duration delay) {}
    }

    /** One API key covers every IDIM proxy environment, so it sits outside the instances. */
    public record IdimProxy(Instance test, Instance prod, String apiKey, Timeouts timeouts) {
      public record Instance(String baseUrl) {}
    }

    public record GcNotify(
        String baseUrl,
        String apiKey,
        /** Template for an end-user access grant. */
        String grantAccessTemplateId,
        /** Template for a delegated-admin grant; explains the admin capability. */
        String grantDelegatedAdminTemplateId,
        Timeouts timeouts) {}

    public record Timeouts(Duration connect, Duration read) {}
  }

  /** Shared-secret access for the CMENG user-info update endpoint. */
  public record UpdateUserInfo(String apiKey, String requesterName) {}
}
