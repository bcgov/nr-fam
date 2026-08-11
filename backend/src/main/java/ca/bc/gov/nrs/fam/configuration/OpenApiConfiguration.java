package ca.bc.gov.nrs.fam.configuration;

import ca.bc.gov.nrs.fam.security.Requester;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The generated OpenAPI document is the single source of truth for the
 * frontend's generated TypeScript client (see {@code client-code-gen}).
 *
 * <p>Upstream ran two FastAPI apps and produced two specs
 * ({@code app-access-control-openapi.json} and
 * {@code admin-management-openapi.json}). This service produces one.
 */
@Configuration
public class OpenApiConfiguration {

  private static final String BEARER_SCHEME = "bearerAuth";
  private static final String API_KEY_SCHEME = "apiKey";

  static {
    // Requester is resolved from the validated access token by
    // RequesterArgumentResolver, never from the request. Without this springdoc
    // sees an unannotated controller parameter and documents it as a query
    // parameter on every endpoint - which would put a bogus `requester` argument
    // on every method of the generated client, and misrepresent a server-derived
    // identity as client-supplied.
    SpringDocUtils.getConfig().addRequestWrapperToIgnore(Requester.class);
  }

  /**
   * Makes the generated schema use the application's Jackson configuration.
   *
   * <p>Without this, swagger-core builds schemas from the Java property names and
   * ignores the {@code SNAKE_CASE} naming strategy - so the document would
   * advertise {@code userName} while the API actually serialises {@code user_name}.
   * Since this document generates the frontend's client, that mismatch would
   * produce a client that silently fails to read every response field.
   */
  @Bean
  public ModelResolver modelResolver(ObjectMapper objectMapper) {
    return new ModelResolver(objectMapper);
  }

  @Bean
  public OpenAPI famOpenApi() {
    return new OpenAPI()
        // Declared explicitly because springdoc otherwise infers the server from
        // the request it is answering. During spec generation that request comes
        // from a RANDOM_PORT test, so the document would record a dead
        // "http://localhost:<random>" that changes on every regeneration - making
        // the committed spec differ from a freshly generated one every time, and
        // failing the CI job that diffs them.
        //
        // A relative URL is also the correct value: the frontend reaches this
        // service through Caddy on the same origin, never by absolute host.
        .servers(List.of(new Server()
            .url("/")
            .description("Relative to the host serving this document")))
        .info(new Info()
            .title("Forest Access Management - FAM - API")
            .description("""
                Forest Access Management API. Defines who has access to which \
                applications, and the roles they operate under once access is \
                granted.

                Covers both the app-access-control and admin-management surfaces \
                that upstream FAM served from two separate APIs.""")
            .version("1.0.0")
            .contact(new Contact()
                .name("Team Heartwood")
                .url("https://apps.nrs.gov.bc.ca/int/confluence/display/FSAST1/Team+Heartwood")
                .email("SIBIFSAF@Victoria1.gov.bc.ca"))
            .license(new License()
                .name("Apache 2.0")
                .url("https://www.apache.org/licenses/LICENSE-2.0.html")))
        .components(new Components()
            .addSecuritySchemes(BEARER_SCHEME,
                new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Access token issued by BC Gov SSO (Keycloak)."))
            // Only the bulk user-information refresh uses this: it is driven by a
            // scheduled job, so there is no signed-in user to carry a token.
            .addSecuritySchemes(API_KEY_SCHEME,
                new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER)
                    .name("X-API-Key")
                    .description("Shared secret for the scheduled user-information refresh.")))
        .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
  }
}
