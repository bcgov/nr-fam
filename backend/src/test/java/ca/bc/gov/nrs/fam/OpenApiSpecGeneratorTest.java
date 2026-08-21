package ca.bc.gov.nrs.fam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Writes the OpenAPI document to {@code ../client-code-gen/fam-openapi.json}.
 *
 * <p>The document is the single source of truth for the frontend's generated
 * TypeScript client, so it is produced from the <em>running application</em>
 * rather than hand-maintained - a controller signature change cannot drift from
 * the client without this test rewriting the spec.
 *
 * <p>Runs against H2 rather than PostgreSQL. springdoc builds the document from
 * controller and DTO signatures, so no real data is needed; H2 only has to let
 * the context start. That keeps spec generation runnable without Docker, unlike
 * {@code SchemaValidationIT}.
 *
 * <p>Regenerate with:
 * <pre>./mvnw test -Dtest=OpenApiSpecGeneratorTest</pre>
 * then regenerate the client with {@code npm run gen-api-client} in
 * {@code frontend/client-code-gen}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("openapi")
@DisplayName("OpenAPI spec generation")
class OpenApiSpecGeneratorTest {

  private static final Path SPEC_PATH =
      Path.of("..", "frontend", "client-code-gen", "fam-openapi.json");

  @Autowired
  private TestRestTemplate restTemplate;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("writes a spec covering every controller")
  void writesOpenApiSpec() throws Exception {
    String spec = restTemplate.getForObject("/v3/api-docs", String.class);
    assertThat(spec).as("springdoc returned no document").isNotBlank();

    JsonNode document = objectMapper.readTree(spec);
    JsonNode paths = document.get("paths");

    assertThat(paths).as("spec has no paths").isNotNull();

    // Every controller must appear. A missing entry means the endpoint would be
    // absent from the generated client too.
    //
    // Applications, roles and role assignments moved to CSS in V94, so the
    // endpoints that served them from FAM's own tables are gone. So is
    // /users/users-information: it refreshed fam_user, which no longer exists.
    assertThat(paths.has("/auth/login")).isTrue();
    assertThat(paths.has("/auth/self")).isTrue();
    assertThat(paths.has("/identity-lookup/idir")).isTrue();
    assertThat(paths.has("/permission-audit-history")).isTrue();
    assertThat(paths.has("/districts")).isTrue();

    // CSS-sourced applications, roles and assignments.
    assertThat(paths.has("/css-applications")).isTrue();
    assertThat(paths.has("/css-applications/{integrationId}/{environment}/roles")).isTrue();
    assertThat(paths.has(
        "/css-applications/{integrationId}/{environment}/user-role-assignments")).isTrue();

    // Retired with the tables behind them.
    assertThat(paths.has("/user-role-assignment")).isFalse();
    assertThat(paths.has("/access-control-privileges")).isFalse();
    assertThat(paths.has("/application-admins")).isFalse();
    assertThat(paths.has("/user-terms-conditions")).isFalse();
    // The external API read from fam_role and fam_user_role_xref, so it went
    // with them. Downstream applications read their roles from the token now.
    assertThat(paths.has("/external/v1/users")).isFalse();

    // Pretty-printed so the committed spec diffs readably.
    Files.createDirectories(SPEC_PATH.getParent());
    Files.writeString(SPEC_PATH,
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n");
  }

  @Test
  @DisplayName("records a stable server url, so regeneration is deterministic")
  void recordsAStableServerUrl() throws Exception {
    // springdoc infers the server from the request it is answering. Under
    // RANDOM_PORT that is a dead "http://localhost:<random>" which changes on
    // every run, so the committed spec would never match a fresh one and the CI
    // job that diffs them could never pass.
    String spec = restTemplate.getForObject("/v3/api-docs", String.class);
    JsonNode servers = objectMapper.readTree(spec).get("servers");

    assertThat(servers).hasSize(1);
    assertThat(servers.get(0).get("url").asText())
        .isEqualTo("/")
        .doesNotContain("localhost");
  }

  @Test
  @DisplayName("serialises DTO properties as snake_case, as the frontend expects")
  void specUsesSnakeCase() throws Exception {
    String spec = restTemplate.getForObject("/v3/api-docs", String.class);
    JsonNode schemas = objectMapper.readTree(spec).get("components").get("schemas");

    JsonNode row = schemas.get("CssUserRoleRowDto");
    assertThat(row).as("CssUserRoleRowDto missing from spec").isNotNull();

    // camelCase here would mean the whole generated client mismatches the API.
    assertThat(row.get("properties").fieldNames()).toIterable()
        .contains("first_name", "last_name", "role_name", "scope_type", "scope_value");
  }
}
