package ca.bc.gov.nrs.fam.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@DisplayName("ForestClientIntegrationService (port of forest_client_integration.py)")
class ForestClientIntegrationServiceTest {

  private MockWebServer server;
  private ForestClientIntegrationService service;

  private static MockResponse json(int code, String body) {
    return new MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    service = buildService(Duration.ofSeconds(2), 2, Duration.ofMillis(10));
  }

  private ForestClientIntegrationService buildService(
      Duration readTimeout, int maxAttempts, Duration retryDelay) {

    String baseUrl = server.url("/").toString();
    FamProperties.Integration.ForestClient config =
        new FamProperties.Integration.ForestClient(
            new FamProperties.Integration.ForestClient.Instance(baseUrl, "test-token"),
            new FamProperties.Integration.ForestClient.Instance(baseUrl, "prod-token"),
            new FamProperties.Integration.Timeouts(Duration.ofSeconds(2), readTimeout),
            new FamProperties.Integration.ForestClient.Retry(maxAttempts, retryDelay));

    FamProperties properties = new FamProperties("dev", null,
        new FamProperties.Integration(config, null, null, null));

    ObjectMapper objectMapper = new ObjectMapper();
    ForestClientIntegrationService created = new ForestClientIntegrationService(
        properties, new RestClientFactory(), new UpstreamErrorTranslator(objectMapper),
        objectMapper);
    created.initClients();
    return created;
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  @DisplayName("sends one repeated id parameter per client number, plus the API key")
  void buildsSearchRequest() throws Exception {
    server.enqueue(json(200, "[]"));

    service.search(List.of("00001011", "00001012"), ApiInstanceEnv.TEST, false);

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("GET");
    assertThat(request.getPath())
        .contains("/api/clients/search")
        .contains("id=00001011")
        .contains("id=00001012")
        .contains("page=0");
    assertThat(request.getHeader("X-API-KEY")).isEqualTo("test-token");
  }

  @Test
  @DisplayName("uses the requested page size so a large lookup fits on one page")
  void honoursExplicitSize() throws Exception {
    server.enqueue(json(200, "[]"));

    service.search(List.of("00001011"), 120, ApiInstanceEnv.TEST, false);

    assertThat(server.takeRequest().getPath()).contains("size=120");
  }

  @Test
  @DisplayName("parses a successful result")
  void parsesResults() {
    server.enqueue(json(200, """
        [{"clientNumber":"00001011","clientName":"AKIECA EXPLORERS LTD.",
          "clientStatusCode":"ACT","clientTypeCode":"C"}]"""));

    List<Map<String, Object>> results =
        service.search(List.of("00001011"), ApiInstanceEnv.TEST, false);

    assertThat(results).hasSize(1);
    assertThat(results.get(0))
        .containsEntry("clientNumber", "00001011")
        .containsEntry("clientName", "AKIECA EXPLORERS LTD.");
  }

  @Test
  @DisplayName("treats 404 as no match, not as an error")
  void notFoundBecomesEmptyList() {
    // This API uses 404 for "no such client" rather than "no such endpoint".
    server.enqueue(json(404, "{\"message\":\"not found\"}"));

    assertThat(service.search(List.of("99999999"), ApiInstanceEnv.TEST, false)).isEmpty();
  }

  @Test
  @DisplayName("treats 400 as no match, since FAM searches a free-text field")
  void badRequestBecomesEmptyList() {
    server.enqueue(json(400, "{\"message\":\"invalid client number\"}"));

    assertThat(service.search(List.of("abcde001"), ApiInstanceEnv.TEST, false)).isEmpty();
  }

  @Test
  @DisplayName("relays a 500 as an upstream failure")
  void serverErrorPropagates() {
    server.enqueue(json(500, "{\"failureCode\":\"FC_BOOM\",\"message\":\"upstream broke\"}"));

    assertThatThrownBy(() -> service.search(List.of("00001011"), ApiInstanceEnv.TEST, false))
        .isInstanceOf(UpstreamException.class)
        .extracting("status", "failureCode", "message")
        .containsExactly(HttpStatus.INTERNAL_SERVER_ERROR, "FC_BOOM", "upstream broke");
  }

  @Test
  @DisplayName("reports an upstream 403 as 500 so the frontend does not log the user out")
  void upstreamForbiddenIsReportedAsInternalError() {
    server.enqueue(json(403, "{\"message\":\"bad api key\"}"));

    assertThatThrownBy(() -> service.search(List.of("00001011"), ApiInstanceEnv.TEST, false))
        .isInstanceOf(UpstreamException.class)
        .extracting("status")
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Test
  @DisplayName("retries once on a read timeout when retry is enabled")
  void retriesOnTimeoutWhenEnabled() throws Exception {
    service = buildService(Duration.ofMillis(300), 2, Duration.ofMillis(10));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    server.enqueue(json(200, "[{\"clientNumber\":\"00001011\",\"clientName\":\"OK\"}]"));

    List<Map<String, Object>> results =
        service.search(List.of("00001011"), ApiInstanceEnv.TEST, true);

    assertThat(results).hasSize(1);
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("does not retry when retry is disabled, and surfaces a 504")
  void doesNotRetryWhenDisabled() {
    service = buildService(Duration.ofMillis(300), 2, Duration.ofMillis(10));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

    assertThatThrownBy(() -> service.search(List.of("00001011"), ApiInstanceEnv.TEST, false))
        .isInstanceOf(UpstreamException.class)
        .extracting("status", "failureCode")
        .containsExactly(HttpStatus.GATEWAY_TIMEOUT, ErrorCode.UPSTREAM_TIMEOUT);

    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("gives up after the configured attempts and reports a 504")
  void failsAfterExhaustingRetries() {
    service = buildService(Duration.ofMillis(300), 2, Duration.ofMillis(10));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

    assertThatThrownBy(() -> service.search(List.of("00001011"), ApiInstanceEnv.TEST, true))
        .isInstanceOf(UpstreamException.class)
        .extracting("status")
        .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);

    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  /** Captures what the service logs during initClients(). */
  private static List<ILoggingEvent> logsFrom(Runnable init) {
    Logger logger = (Logger) LoggerFactory.getLogger(ForestClientIntegrationService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      init.run();
      return List.copyOf(appender.list);
    } finally {
      logger.detachAppender(appender);
    }
  }

  private ForestClientIntegrationService serviceWith(String testUrl, String prodUrl) {
    FamProperties.Integration.ForestClient config =
        new FamProperties.Integration.ForestClient(
            new FamProperties.Integration.ForestClient.Instance(testUrl, "t"),
            new FamProperties.Integration.ForestClient.Instance(prodUrl, "p"),
            new FamProperties.Integration.Timeouts(Duration.ofSeconds(1), Duration.ofSeconds(1)),
            new FamProperties.Integration.ForestClient.Retry(1, Duration.ofMillis(1)));
    ObjectMapper objectMapper = new ObjectMapper();
    return new ForestClientIntegrationService(
        new FamProperties("dev", null, new FamProperties.Integration(config, null, null, null)),
        new RestClientFactory(), new UpstreamErrorTranslator(objectMapper), objectMapper);
  }

  @Test
  @DisplayName("an unconfigured TEST instance warns, naming the variable that sets it")
  void missingTestEndpointWarns() {
    // Every environment uses the TEST instance, so a blank endpoint is always a
    // misconfiguration - and the only other symptom is searches failing later.
    // Logged at INFO it would sit unread among the startup lines.
    ForestClientIntegrationService service = serviceWith("", "https://prod.example");

    List<ILoggingEvent> logs = logsFrom(service::initClients);

    assertThat(logs).anySatisfy(event -> {
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage())
          .contains("TEST instance not configured")
          .contains("fc_api_base_url_test");
    });
  }

  @Test
  @DisplayName("an unconfigured PROD instance does not warn - that is the normal state")
  void missingProdEndpointDoesNotWarn() {
    // A lower environment holding no PROD endpoint is the point, not a fault.
    // Warning about it would train people to ignore the warning that matters.
    ForestClientIntegrationService service = serviceWith("https://test.example", "");

    List<ILoggingEvent> logs = logsFrom(service::initClients);

    assertThat(logs).noneMatch(event -> event.getLevel() == Level.WARN);
    assertThat(logs).anySatisfy(event -> {
      assertThat(event.getLevel()).isEqualTo(Level.INFO);
      assertThat(event.getFormattedMessage()).contains("PROD instance not configured");
    });
  }

  @Test
  @DisplayName("fails clearly when the requested instance was never configured")
  void unconfiguredInstanceFails() {
    FamProperties.Integration.ForestClient config =
        new FamProperties.Integration.ForestClient(
            new FamProperties.Integration.ForestClient.Instance(
                server.url("/").toString(), "test-token"),
            new FamProperties.Integration.ForestClient.Instance("", ""),
            new FamProperties.Integration.Timeouts(Duration.ofSeconds(1), Duration.ofSeconds(1)),
            new FamProperties.Integration.ForestClient.Retry(1, Duration.ofMillis(1)));

    ObjectMapper objectMapper = new ObjectMapper();
    ForestClientIntegrationService unconfigured = new ForestClientIntegrationService(
        new FamProperties("dev", null, new FamProperties.Integration(config, null, null, null)),
        new RestClientFactory(), new UpstreamErrorTranslator(objectMapper), objectMapper);
    unconfigured.initClients();

    assertThatThrownBy(() -> unconfigured.search(List.of("1"), ApiInstanceEnv.PROD, false))
        .isInstanceOf(UpstreamException.class)
        .hasMessageContaining("PROD instance is not configured");
  }

  @Test
  @DisplayName("searches name and number together on the substring endpoint")
  void buildsSubstringSearchRequest() throws Exception {
    server.enqueue(json(200, "[]"));

    service.searchByNumberOrName("Acme Forestry", 10, ApiInstanceEnv.TEST);

    RecordedRequest request = server.takeRequest();
    // Not /api/clients/search/by, which looks right from its parameter list but
    // matches names by Jaro-Winkler similarity and numbers exactly - so "ser"
    // answered with REYBURN and SWAIN, and "000" answered with nothing.
    // findByClientNumberOrName is the one whose query is a LIKE %term% across
    // both fields.
    assertThat(request.getPath())
        .startsWith("/api/clients/findByClientNumberOrName/")
        .contains("size=10")
        .doesNotContain("search/by");
  }

  @Test
  @DisplayName("upper-cases the term, because the endpoint does not")
  void upperCasesTheTerm() throws Exception {
    server.enqueue(json(200, "[]"));

    service.searchByNumberOrName("acme", 10, ApiInstanceEnv.TEST);

    // The endpoint passes the term to the database untouched and the legacy
    // names are stored upper-case, so a lower-case term matches nothing at all.
    assertThat(server.takeRequest().getPath()).contains("/ACME");
  }

  @Test
  @DisplayName("encodes a term that would otherwise break the path")
  void encodesThePathSegment() throws Exception {
    server.enqueue(json(200, "[]"));

    // The term travels in the path, not the query string, and people type
    // slashes and spaces into a search box.
    service.searchByNumberOrName("a/b c", 10, ApiInstanceEnv.TEST);

    String path = server.takeRequest().getPath();
    assertThat(path).doesNotContain("/A/B");
    assertThat(path).contains("%2FB");
  }

  @Test
  @DisplayName("searches a partial number without padding it")
  void buildsNumberSearchRequest() throws Exception {
    server.enqueue(json(200, "[]"));

    service.searchByNumberOrName("123", 10, ApiInstanceEnv.TEST);

    RecordedRequest request = server.takeRequest();
    // A substring match finds 00001234 from "123" on its own; padding to
    // 00000123 would turn that into a lookup for a different client.
    assertThat(request.getPath())
        .contains("/123")
        .doesNotContain("00000123");
  }

  @Test
  @DisplayName("looks an acronym up on its own endpoint")
  void buildsAcronymSearchRequest() throws Exception {
    server.enqueue(json(200, "[]"));

    service.searchByAcronym("bcts", 10, ApiInstanceEnv.TEST);

    RecordedRequest request = server.takeRequest();
    // No substring endpoint covers the acronym, and the match is exact, so this
    // stays on search/by - upper-cased, since the stored acronyms are.
    assertThat(request.getPath())
        .startsWith("/api/clients/search/by")
        .contains("acronym=BCTS");
  }

  @Test
  @DisplayName("returns what the search matched")
  void returnsNameMatches() throws Exception {
    server.enqueue(json(200, """
        [{"clientNumber":"00001011","clientName":"ACME FORESTRY LTD.",
          "clientStatusCode":"ACT"}]"""));

    assertThat(service.searchByNumberOrName("acme", 10, ApiInstanceEnv.TEST))
        .singleElement()
        .satisfies(client ->
            assertThat(client.get("clientName")).isEqualTo("ACME FORESTRY LTD."));
  }

  @Test
  @DisplayName("a refusal from the search endpoint is no match, not an error")
  void treatsRefusalAsNoMatch() throws Exception {
    // The API answers 400 and 404 for "nothing matched". Surfacing those as
    // errors would put a failure banner over an ordinary empty search.
    server.enqueue(json(400, "{}"));

    assertThat(service.searchByNumberOrName("nothing", 10, ApiInstanceEnv.TEST)).isEmpty();
  }
}
