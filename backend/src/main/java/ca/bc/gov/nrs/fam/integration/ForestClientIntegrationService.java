package ca.bc.gov.nrs.fam.integration;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Client for the BC Forest Client API.
 *
 * <p>Port of {@code integration/forest_client_integration.py}. API directory
 * entry: https://api.gov.bc.ca/devportal/api-directory/3179 - the key is issued
 * from that portal.
 *
 * <p>FAM PROD serves DEV, TEST and PROD applications, but this API publishes only
 * TEST and PROD instances; see {@link ApiInstanceEnv} for how one is chosen.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForestClientIntegrationService {

  private static final String UPSTREAM = "forest-client-api";

  private final FamProperties famProperties;
  private final RestClientFactory restClientFactory;
  private final UpstreamErrorTranslator errorTranslator;
  private final ObjectMapper objectMapper;

  private final Map<ApiInstanceEnv, RestClient> clients =
      new EnumMap<>(ApiInstanceEnv.class);

  @PostConstruct
  void initClients() {
    FamProperties.Integration.ForestClient config =
        famProperties.integration().forestClient();

    for (ApiInstanceEnv env : ApiInstanceEnv.values()) {
      FamProperties.Integration.ForestClient.Instance instance =
          env == ApiInstanceEnv.PROD ? config.prod() : config.test();

      if (instance == null || instance.baseUrl() == null || instance.baseUrl().isBlank()) {
        // A FAM environment that never calls the PROD instance has no PROD config.
        // Skip it rather than failing startup; calling it later fails loudly.
        log.info("Forest Client API {} instance not configured", env);
        continue;
      }

      clients.put(env, restClientFactory.create(
          instance.baseUrl(),
          config.timeouts().connect(),
          config.timeouts().read(),
          headers -> {
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("X-API-KEY", instance.apiToken());
          }));
    }
  }

  /**
   * Look up forest clients by number.
   *
   * <p>The API's responses are quirky and the quirks are load-bearing:
   *
   * <ul>
   *   <li>an unknown, malformed or wrong-length number returns 200 with
   *       {@code []}, not an error;
   *   <li>404 means "no such client" rather than "no such endpoint", and 400
   *       means "invalid client number" - FAM searches by a free-text field that
   *       may be either, so both are normalised to an empty result;
   *   <li>a mixed request returns only the numbers that matched.
   * </ul>
   *
   * @param retryOnTimeout retry once on a timeout or connection failure. Used for
   *     background enrichment, where a retry is cheap; left off for
   *     user-facing lookups, where latency matters more.
   */
  public List<Map<String, Object>> search(
      List<String> forestClientNumbers, ApiInstanceEnv apiInstanceEnv, boolean retryOnTimeout) {
    return search(forestClientNumbers, FamConstants.DEFAULT_FC_API_SEARCH_PAGE_SIZE,
        apiInstanceEnv, retryOnTimeout);
  }

  /**
   * @param size page size to request. Callers enriching a result set pass the
   *     number of clients they are looking up, which can exceed the default 50;
   *     the API returns them all on the first page, so no paging is needed.
   */
  public List<Map<String, Object>> search(
      List<String> forestClientNumbers, int size, ApiInstanceEnv apiInstanceEnv,
      boolean retryOnTimeout) {

    FamProperties.Integration.ForestClient.Retry retry =
        famProperties.integration().forestClient().retry();
    int maxAttempts = retryOnTimeout ? retry.maxAttempts() : 1;

    for (int attempt = 1; ; attempt++) {
      try {
        return doSearch(forestClientNumbers, size, apiInstanceEnv);
      } catch (ResourceAccessException e) {
        if (attempt >= maxAttempts) {
          log.error("Forest Client API request failed after {} attempt(s)", attempt, e);
          throw errorTranslator.connectivityFailure(UPSTREAM, e);
        }
        log.warn("Forest Client API request failed ({}). Retrying in {} (attempt {}/{})",
            e.getClass().getSimpleName(), retry.delay(), attempt, maxAttempts);
        sleep(retry.delay().toMillis());
      }
    }
  }

  private List<Map<String, Object>> doSearch(
      List<String> forestClientNumbers, int size, ApiInstanceEnv apiInstanceEnv) {

    RestClient client = clientFor(apiInstanceEnv);

    UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/api/clients/search")
        .queryParam("page", FamConstants.DEFAULT_FC_API_SEARCH_PAGE)
        .queryParam("size", size);
    // Repeated "id" parameters, one per client number: &id=00001011&id=00001012
    forestClientNumbers.forEach(number -> uri.queryParam("id", number));

    ResponseEntity<byte[]> response = client.get()
        .uri(uri.build().toUriString())
        .retrieve()
        .toEntity(byte[].class);

    HttpStatus status = HttpStatus.valueOf(response.getStatusCode().value());
    if (status.isError()) {
      // 404 "client not found" and 400 "invalid client number" are ordinary
      // no-match outcomes for a free-text search, not failures.
      if (status == HttpStatus.NOT_FOUND || status == HttpStatus.BAD_REQUEST) {
        log.debug("Forest Client API returned {} for {}; treating as no match",
            status.value(), forestClientNumbers);
        return List.of();
      }
      throw errorTranslator.httpError(
          UPSTREAM, response.getStatusCode(), response.getBody(), status.getReasonPhrase());
    }

    return parseBody(response.getBody());
  }

  private List<Map<String, Object>> parseBody(byte[] body) {
    if (body == null || body.length == 0) {
      return List.of();
    }
    try {
      return objectMapper.readValue(body, new TypeReference<List<Map<String, Object>>>() {});
    } catch (IOException e) {
      throw new UpstreamException(HttpStatus.BAD_GATEWAY, null,
          "Unreadable response from Forest Client API: "
              + new String(body, StandardCharsets.UTF_8),
          UPSTREAM, e);
    }
  }

  private RestClient clientFor(ApiInstanceEnv env) {
    RestClient client = clients.get(env);
    if (client == null) {
      throw new UpstreamException(HttpStatus.INTERNAL_SERVER_ERROR, null,
          "Forest Client API " + env + " instance is not configured.", UPSTREAM);
    }
    return client;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting to retry", e);
    }
  }
}
