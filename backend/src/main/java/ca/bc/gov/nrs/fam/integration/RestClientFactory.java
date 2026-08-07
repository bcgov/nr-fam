package ca.bc.gov.nrs.fam.integration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Builds a {@link RestClient} per upstream service with explicit connect and read
 * timeouts.
 *
 * <p>An integration without both timeouts can hang a request thread indefinitely,
 * so they are always set rather than defaulted. Each upstream gets its own client
 * because base URL, credentials and timeout budget all differ.
 */
@Component
public class RestClientFactory {

  public RestClient create(
      String baseUrl, Duration connectTimeout, Duration readTimeout,
      Consumer<HttpHeaders> defaultHeaders) {

    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
        HttpClient.newBuilder().connectTimeout(connectTimeout).build());
    requestFactory.setReadTimeout(readTimeout);

    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(requestFactory)
        .defaultHeaders(defaultHeaders)
        // Error responses are translated per call site by UpstreamErrorTranslator,
        // which needs the raw body; the default handler would consume it first.
        .defaultStatusHandler(status -> true, (request, response) -> { })
        .build();
  }
}
