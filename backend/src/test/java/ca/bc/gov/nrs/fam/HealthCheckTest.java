package ca.bc.gov.nrs.fam;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HealthCheck (the container's own actuator probe)")
class HealthCheckTest {

  /** Runs a one-shot server answering {@code status}, and returns the exit code. */
  private int probe(int status) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/actuator/health", exchange -> {
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
    });
    server.start();
    try {
      URI endpoint =
          URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/actuator/health");
      return HealthCheck.check(endpoint);
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("healthy when the endpoint answers 200")
  void healthyOnOk() throws IOException {
    assertThat(probe(200)).isZero();
  }

  @Test
  @DisplayName("unhealthy when the application reports itself down")
  void unhealthyOnServiceUnavailable() throws IOException {
    assertThat(probe(503)).isEqualTo(1);
  }

  @Test
  @DisplayName("unhealthy when nothing is listening")
  void unhealthyWhenNothingListens() {
    assertThat(HealthCheck.check(URI.create("http://127.0.0.1:1/actuator/health"))).isEqualTo(1);
  }

  @Test
  @DisplayName("probes port 8080 - server.port - by default")
  void defaultsToTheApplicationPort() {
    assertThat(HealthCheck.endpoint())
        .hasToString("http://127.0.0.1:8080/actuator/health");
  }
}
