package ca.bc.gov.nrs.fam.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.dto.CssIntegrationDto;
import ca.bc.gov.nrs.fam.dto.CssRoleDto;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CssApiService (port of css_api.py)")
class CssApiServiceTest {

  private MockWebServer server;
  private CssApiService service;

  private static final String TOKEN_BODY =
      "{\"access_token\":\"tok-1\",\"expires_in\":300}";

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    service = serviceWith("client-id", "client-secret");
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  private CssApiService serviceWith(String clientId, String clientSecret) {
    String base = server.url("/api/v1").toString();
    String tokenUrl = server.url("/token").toString();

    FamProperties.Integration.Css css = new FamProperties.Integration.Css(
        base, tokenUrl, clientId, clientSecret, null,
        new FamProperties.Integration.Css.IdpAliases(null, null),
        new FamProperties.Integration.Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(2)));

    FamProperties properties = new FamProperties("dev", null,
        new FamProperties.Integration(null, css, null, null), null);

    // The app's ObjectMapper, not a default one. FAM's own API is snake_case and
    // that strategy is global, so the CSS client deserialises upstream responses
    // with it too. A default mapper here would pass while the running app fails.
    ObjectMapper objectMapper = new ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    CssApiService created = new CssApiService(
        properties, new RestClientFactory(), new UpstreamErrorTranslator(objectMapper),
        objectMapper);
    created.initClients();
    return created;
  }

  private void enqueueToken() {
    server.enqueue(new MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(TOKEN_BODY));
  }

  private void enqueueJson(String body) {
    server.enqueue(new MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(body));
  }

  @Test
  @DisplayName("unwraps the data envelope on a list response")
  void unwrapsDataEnvelope() {
    enqueueToken();
    enqueueJson("""
        {"data":[{"id":6538,"projectName":"FREP","environments":["dev","test"],
                  "status":"applied"}]}""");

    List<CssIntegrationDto> integrations = service.getIntegrations();

    assertThat(integrations).singleElement().satisfies(i -> {
      assertThat(i.id()).isEqualTo(6538);
      assertThat(i.projectName()).isEqualTo("FREP");
      assertThat(i.environments()).containsExactly("dev", "test");
    });
  }

  @Test
  @DisplayName("reads a missing or null data envelope as empty, not as an error")
  void missingEnvelopeIsEmpty() {
    enqueueToken();
    enqueueJson("{}");
    assertThat(service.getIntegrations()).isEmpty();

    enqueueJson("{\"data\":null}");
    assertThat(service.getIntegrations()).isEmpty();
  }

  @Test
  @DisplayName("ignores unknown fields, so a CSS API addition does not break the client")
  void ignoresUnknownFields() {
    enqueueToken();
    enqueueJson("""
        {"data":[{"id":1,"projectName":"X","environments":["dev"],
                  "somethingCssAddedLater":{"nested":true}}]}""");

    assertThat(service.getIntegrations()).singleElement()
        .satisfies(i -> assertThat(i.projectName()).isEqualTo("X"));
  }

  @Test
  @DisplayName("sends client_credentials and reuses the token across calls")
  void reusesToken() throws Exception {
    enqueueToken();
    enqueueJson("{\"data\":[]}");
    enqueueJson("{\"data\":[]}");

    service.getIntegrations();
    service.getIntegrations();

    RecordedRequest tokenRequest = server.takeRequest();
    assertThat(tokenRequest.getPath()).isEqualTo("/token");
    String form = tokenRequest.getBody().readUtf8();
    assertThat(form).contains("grant_type=client_credentials")
        .contains("client_id=client-id")
        .contains("client_secret=client-secret");

    // Two API calls, but only the one token request above: 3 total, not 4.
    assertThat(server.getRequestCount()).isEqualTo(3);

    RecordedRequest first = server.takeRequest();
    assertThat(first.getHeader("Authorization")).isEqualTo("Bearer tok-1");
    assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer tok-1");
  }

  @Test
  @DisplayName("builds the environment-scoped role path")
  void buildsRolePath() throws Exception {
    enqueueToken();
    enqueueJson("{\"data\":[{\"name\":\"FREP_EDITOR\",\"composite\":false}]}");

    List<CssRoleDto> roles = service.getRoles(6538, "dev");

    assertThat(roles).singleElement().satisfies(r -> {
      assertThat(r.name()).isEqualTo("FREP_EDITOR");
      assertThat(r.composite()).isFalse();
    });

    server.takeRequest(); // token
    assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/integrations/6538/dev/roles");
  }

  @Test
  @DisplayName("URL-encodes a role name that would otherwise break the path")
  void encodesRoleNameInPath() throws Exception {
    enqueueToken();
    enqueueJson("{\"data\":[]}");

    // A generated scope role carries the scope value, which is not guaranteed to
    // be path safe.
    service.getUsersWithRole(1, "dev", "ROLE_FOREST_CLIENT-00001018/X");

    server.takeRequest();
    assertThat(server.takeRequest().getPath())
        .contains("ROLE_FOREST_CLIENT-00001018%2FX");
  }

  @Test
  @DisplayName("treats 409 on role creation as success, since the role now exists")
  void createRoleTreats409AsSuccess() {
    enqueueToken();
    server.enqueue(new MockResponse().setResponseCode(409).setBody("{\"message\":\"exists\"}"));

    // Find-or-create: another request winning the race is the desired end state.
    service.createRole(1, "dev", "FREP_EDITOR");
  }

  @Test
  @DisplayName("surfaces a non-409 error from role creation")
  void createRolePropagatesRealErrors() {
    enqueueToken();
    server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"message\":\"boom\"}"));

    assertThatThrownBy(() -> service.createRole(1, "dev", "FREP_EDITOR"))
        .isInstanceOf(UpstreamException.class);
  }

  @Test
  @DisplayName("posts role assignments as a name array")
  void assignsRolesAsNameArray() throws Exception {
    enqueueToken();
    enqueueJson("{\"data\":[]}");

    service.assignUserRoles(1, "dev", "abc@idir", List.of("R1", "R2"));

    server.takeRequest();
    RecordedRequest request = server.takeRequest();
    assertThat(request.getPath()).isEqualTo("/api/v1/integrations/1/dev/users/abc%40idir/roles");
    assertThat(request.getBody().readUtf8()).isEqualTo("[{\"name\":\"R1\"},{\"name\":\"R2\"}]");
  }

  @Test
  @DisplayName("maps composite-role children down to their names")
  void mapsCompositesToNames() {
    enqueueToken();
    enqueueJson("""
        {"data":[{"name":"CHR_FREP_EDITOR","composite":true},
                 {"name":"HAS_DISTRICT_ROLE","composite":false}]}""");

    assertThat(service.getRoleComposites(1, "dev", "Submitter (CHR)"))
        .containsExactly("CHR_FREP_EDITOR", "HAS_DISTRICT_ROLE");
  }

  @Test
  @DisplayName("fails clearly when credentials are absent rather than calling out")
  void failsClearlyWhenUnconfigured() {
    CssApiService unconfigured = serviceWith("", "");

    assertThat(unconfigured.isConfigured()).isFalse();
    assertThatThrownBy(unconfigured::getIntegrations)
        .isInstanceOf(UpstreamException.class)
        .hasMessageContaining("CSS_CLIENT_ID");

    // Nothing was attempted over the wire.
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  @DisplayName("rejects a token response with no access_token")
  void rejectsTokenResponseWithoutToken() {
    server.enqueue(new MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody("{\"expires_in\":300}"));

    assertThatThrownBy(() -> service.getIntegrations())
        .isInstanceOf(UpstreamException.class)
        .hasMessageContaining("no access_token");
  }

  @Test
  @DisplayName("reports an unreadable body as an upstream failure, not a parse crash")
  void unreadableBodyBecomesUpstreamFailure() {
    enqueueToken();
    enqueueJson("this is not json");

    assertThatThrownBy(() -> service.getIntegrations())
        .isInstanceOf(UpstreamException.class)
        .hasMessageContaining("Unreadable response");
  }
}
