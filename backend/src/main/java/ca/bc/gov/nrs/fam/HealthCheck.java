package ca.bc.gov.nrs.fam;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Probes this container's own actuator health endpoint and reports the verdict
 * as an exit code, for the {@code HEALTHCHECK} in the Dockerfile.
 *
 * <p>The deploy image is distroless: no shell, no {@code curl}, no {@code wget}.
 * What it does have is the JRE that runs the application, and the extracted
 * layout puts the application's own classes in a plain {@code app.jar} on the
 * classpath - so a class using nothing but {@code java.net.http} is the one
 * HTTP client available to a health check.
 *
 * <p>OpenShift ignores {@code HEALTHCHECK} and uses its own liveness and
 * readiness probes; this is for anyone running the image directly, and for
 * scanners that treat a missing {@code HEALTHCHECK} as a finding.
 */
public final class HealthCheck {

  /** Matches {@code server.port}, overridable the same way the app allows. */
  private static final String DEFAULT_PORT = "8080";

  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  private HealthCheck() {}

  public static void main(String[] args) {
    System.exit(check(endpoint()));
  }

  /**
   * @return 0 when the endpoint answers 200, 1 for any other answer or none at
   *     all - the exit codes {@code HEALTHCHECK} reads as healthy and unhealthy.
   */
  static int check(URI endpoint) {
    try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
      HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT).GET().build();
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      return response.statusCode() == 200 ? 0 : 1;
    } catch (IOException | IllegalArgumentException e) {
      return 1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return 1;
    }
  }

  static URI endpoint() {
    String port = System.getenv("SERVER_PORT");
    return URI.create(
        "http://127.0.0.1:" + (port == null || port.isBlank() ? DEFAULT_PORT : port)
            + "/actuator/health");
  }
}
