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
    Integration integration) {

  public record Cors(List<String> allowedOrigins) {}

  public record Integration(
      ForestClient forestClient, Css css, UserLookup userLookup, Smtp smtp) {

    /**
     * nr-user-lookup-api, the BC Gov identity directory. Replaces the IDIM proxy.
     *
     * <p><b>Three instances, one per environment.</b> An earlier note here said
     * the directory was not environment-partitioned and that nothing needed to
     * consult a resolver. That was wrong, and expensively so: BCeID is deployed
     * in three environments and the same person is a different account with a
     * different GUID in each, so a GUID read from the wrong instance is one CSS
     * cannot resolve when it comes to assign the role. The instance to use is
     * decided by the application being administered - see
     * {@code ApiInstanceEnvResolver#resolveDirectory}.
     *
     * <p>Separate hosts and separate service accounts, because each instance sits
     * behind its own Keycloak. That is why the credentials live on the instance
     * rather than beside them: there is no shared account to fall back on, and a
     * token minted against one realm is not accepted by another.
     *
     * <p>Each is a confidential service account. Note this means lookups are
     * attributed to FAM rather than to the person performing them - see the note
     * on {@code UserLookupClient}.
     */
    public record UserLookup(
        Instance dev, Instance test, Instance prod, Timeouts timeouts) {

      /**
       * One deployment of the directory, with the account that reaches it.
       *
       * <p>Unconfigured is a normal state rather than an error: a lower
       * deployment has no production account, and asking it for one is refused
       * where the environment is chosen rather than failing at startup.
       */
      public record Instance(
          String baseUrl,
          String tokenUrl,
          String clientId,
          String clientSecret,
          /** Normally blank: the scopes are default client scopes on the account. */
          String scope) {

        public boolean isConfigured() {
          return baseUrl != null && !baseUrl.isBlank();
        }
      }
    }

    /**
     * BC Gov Common Hosted Single Sign-On API.
     *
     * <p>Source of applications (CSS integrations), roles and role assignments,
     * replacing the FAM tables that held them.
     *
     * <p>Unlike the browser client, this is a <em>confidential</em> client: the
     * API account authenticates with client_credentials, so the secret is
     * backend-only and must never reach {@code env.json}.
     */
    public record Css(
        String apiBaseUrl,
        String tokenUrl,
        String clientId,
        String clientSecret,
        /**
         * FAM's own CSS integration id.
         *
         * <p>Administering FAM itself means administering who administers
         * everything else, so it is reserved to {@code FAM_ADMIN}. FAM has to be
         * told which integration is its own; nothing in a CSS response marks it.
         *
         * <p>Left unset, that protection cannot be applied - see the startup
         * warning in {@code AuthorizationService}.
         */
        Integer ownIntegrationId,
        IdpAliases idpAliases,
        Timeouts timeouts) {

      /**
       * The identity-provider suffixes Keycloak uses in a federated username
       * ({@code <guid>@<alias>}).
       *
       * <p>Configurable because the standard realm carries two IDIR
       * integrations: {@code azureidir} (Entra-backed, what BC Gov IDIR users
       * actually sign in through) and the legacy {@code idir}. CSS has no user
       * search, so FAM has to construct the username exactly.
       *
       * <p><b>The assignment endpoint accepts only {@code azureidir} and
       * {@code bceidbusiness}.</b> Setting the IDIR alias to the legacy
       * {@code idir} makes every grant fail with {@code invalid idp idir}, which
       * is why the default is not it and why an override to it is warned about at
       * startup.
       */
      public record IdpAliases(String idir, String bceidBusiness) {

        public String idir() {
          return idir == null || idir.isBlank() ? "azureidir" : idir;
        }

        public String bceidBusiness() {
          return bceidBusiness == null || bceidBusiness.isBlank()
              ? "bceidbusiness" : bceidBusiness;
        }
      }
    }


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



    /**
     * Outbound email, sent through an SMTP relay.
     *
     * <p>Replaces GC Notify. The relay renders nothing - unlike GC Notify's
     * templates, the body is composed here - so there are no template ids to
     * keep in step with a third party.
     *
     * <p>{@code from} is required to send at all; a relay will reject a message
     * with no envelope sender. Leaving the host blank disables sending, which is
     * the local default.
     */
    public record Smtp(String from, String replyTo) {}

    public record Timeouts(Duration connect, Duration read) {}
  }

}
