package ca.bc.gov.nrs.fam.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
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
        new FamProperties.Integration(config, null, null), null);

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
        new FamProperties("dev", null, new FamProperties.Integration(config, null, null), null),
        new RestClientFactory(), new UpstreamErrorTranslator(objectMapper), objectMapper);
    unconfigured.initClients();

    assertThatThrownBy(() -> unconfigured.search(List.of("1"), ApiInstanceEnv.PROD, false))
        .isInstanceOf(UpstreamException.class)
        .hasMessageContaining("PROD instance is not configured");
  }
}
