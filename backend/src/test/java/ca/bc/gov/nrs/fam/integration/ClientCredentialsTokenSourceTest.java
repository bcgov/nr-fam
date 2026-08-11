package ca.bc.gov.nrs.fam.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ClientCredentialsTokenSource")
class ClientCredentialsTokenSourceTest {

  private MockWebServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  private ClientCredentialsTokenSource source() {
    return ClientCredentialsTokenSource.fromProperties(
        server.url("/token").toString(), "an-id", "a-secret", "",
        Duration.ofSeconds(2), Duration.ofSeconds(2), new RestClientFactory());
  }

  private void enqueue(int status, String body) {
    server.enqueue(new MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body));
  }

  @Test
  @DisplayName("reports the endpoint's own reason when it rejects the client")
  void reportsTheEndpointsReason() {
    // The shared RestClient suppresses error statuses, so without an explicit
    // status check a 401 arrives as "no access_token" and the actual cause is
    // discarded. This is the message that says what to go and fix.
    enqueue(401, """
        {"error":"unauthorized_client",
         "error_description":"Client not enabled to retrieve service account"}""");

    assertThatThrownBy(() -> source().fetch())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("401")
        .hasMessageContaining("unauthorized_client")
        .hasMessageContaining("Client not enabled to retrieve service account");
  }

  @Test
  @DisplayName("reports an error body that is not an OAuth2 error document")
  void reportsNonOauthErrorBody() {
    // A gateway or proxy in front of Keycloak may answer with HTML.
    enqueue(502, "<html><body>Bad Gateway</body></html>");

    assertThatThrownBy(() -> source().fetch())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("502")
        .hasMessageContaining("Bad Gateway");
  }

  @Test
  @DisplayName("truncates a long error body rather than flooding the log")
  void truncatesLongErrorBody() {
    enqueue(500, "x".repeat(5000));

    assertThatThrownBy(() -> source().fetch())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("...");
  }

  @Test
  @DisplayName("still reports a 200 that carries no access_token")
  void reportsSuccessfulResponseWithNoToken() {
    enqueue(200, "{\"expires_in\":300}");

    assertThatThrownBy(() -> source().fetch())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no access_token");
  }

  @Test
  @DisplayName("sends client_credentials with Basic auth")
  void sendsClientCredentials() throws Exception {
    enqueue(200, "{\"access_token\":\"tok\",\"expires_in\":300}");

    assertThat(source().fetch().accessToken()).isEqualTo("tok");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeader("Authorization")).startsWith("Basic ");
    assertThat(request.getBody().readUtf8()).contains("grant_type=client_credentials");
  }

  @Test
  @DisplayName("caches the token instead of minting one per call")
  void cachesTheToken() {
    enqueue(200, "{\"access_token\":\"tok\",\"expires_in\":300}");
    ClientCredentialsTokenSource source = source();

    assertThat(source.fetchCached()).isEqualTo("tok");
    assertThat(source.fetchCached()).isEqualTo("tok");

    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("a failed fetch yields an empty token rather than propagating")
  void failedFetchYieldsEmptyToken() {
    // The caller decides what an unauthenticated call means; the reason is
    // logged, and the next call retries.
    enqueue(401, "{\"error\":\"invalid_client\"}");

    assertThat(source().fetchCached()).isEmpty();
  }

  @Test
  @DisplayName("does not cache a failure, so the next call retries")
  void doesNotCacheFailure() {
    enqueue(401, "{\"error\":\"invalid_client\"}");
    enqueue(200, "{\"access_token\":\"tok\",\"expires_in\":300}");

    ClientCredentialsTokenSource source = source();
    assertThat(source.fetchCached()).isEmpty();
    assertThat(source.fetchCached()).isEqualTo("tok");
  }
}
