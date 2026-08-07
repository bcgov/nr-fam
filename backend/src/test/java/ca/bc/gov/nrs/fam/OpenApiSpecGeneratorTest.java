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
    assertThat(paths.fieldNames()).toIterable().anySatisfy(path ->
        assertThat(path).startsWith("/fam-applications"));
    assertThat(paths.has("/user-role-assignment")).isTrue();
    assertThat(paths.has("/permission-audit-history")).isTrue();
    assertThat(paths.has("/user-terms-conditions")).isTrue();
    assertThat(paths.has("/forest-clients/search")).isTrue();
    assertThat(paths.has("/identity-lookup/idir")).isTrue();
    assertThat(paths.has("/auth/login")).isTrue();
    assertThat(paths.has("/auth/self")).isTrue();
    // Admin-management surface, merged in from the second upstream API.
    assertThat(paths.has("/application-admins")).isTrue();
    assertThat(paths.has("/access-control-privileges")).isTrue();
    assertThat(paths.has("/admin-user-accesses")).isTrue();
    // External API - a published contract for downstream applications.
    assertThat(paths.has("/external/v1/users")).isTrue();
    assertThat(paths.has("/external/v1/users/me/role-metadata")).isTrue();
    assertThat(paths.has("/users/users-information")).isTrue();

    // Pretty-printed so the committed spec diffs readably.
    Files.createDirectories(SPEC_PATH.getParent());
    Files.writeString(SPEC_PATH,
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n");
  }

  @Test
  @DisplayName("exposes the admin models the frontend imports")
  void specCoversAdminModels() throws Exception {
    // These are exactly the models the Vue app still imports from the legacy
    // admin-management client. Their presence is what unblocks the switch to the
    // single generated client.
    String spec = restTemplate.getForObject("/v3/api-docs", String.class);
    JsonNode schemas = objectMapper.readTree(spec).get("components").get("schemas");

    assertThat(schemas.fieldNames()).toIterable().contains(
        "AdminUserAccessResponse",
        "FamAuthGrantDto",
        "FamGrantDetailDto",
        "FamApplicationGrantDto",
        "FamRoleGrantDto",
        "FamForestClientBase",
        "FamAccessControlPrivilegeCreateRequest",
        "FamAppAdminCreateRequest",
        "FamAppAdminGetResponse");
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
  @DisplayName("keeps the external API camelCase, unlike the internal one")
  void externalApiStaysCamelCase() throws Exception {
    // The external API is a published contract for other applications and was
    // camelCase under FastAPI. Only the internal API is snake_case.
    String spec = restTemplate.getForObject("/v3/api-docs", String.class);
    JsonNode schemas = objectMapper.readTree(spec).get("components").get("schemas");

    assertThat(schemas.get("ExtApplicationUserSearchGetDto").get("properties").fieldNames())
        .toIterable().contains("firstName", "lastName", "idpUsername", "idpUserGuid", "idpType");
    assertThat(schemas.get("ExtRoleWithScopeDto").get("properties").fieldNames())
        .toIterable().contains("applicationName", "roleName", "roleDisplayName", "scopeType");
    assertThat(schemas.get("ExtPageResultMeta").get("properties").fieldNames())
        .toIterable().contains("pageCount");
  }

  @Test
  @DisplayName("serialises DTO properties as snake_case, as the frontend expects")
  void specUsesSnakeCase() throws Exception {
    String spec = restTemplate.getForObject("/v3/api-docs", String.class);
    JsonNode schemas = objectMapper.readTree(spec).get("components").get("schemas");

    JsonNode userInfo = schemas.get("FamUserInfoDto");
    assertThat(userInfo).as("FamUserInfoDto missing from spec").isNotNull();

    // camelCase here would mean the whole generated client mismatches the API.
    assertThat(userInfo.get("properties").fieldNames()).toIterable()
        .contains("user_name", "user_type", "first_name", "last_name");
  }
}
