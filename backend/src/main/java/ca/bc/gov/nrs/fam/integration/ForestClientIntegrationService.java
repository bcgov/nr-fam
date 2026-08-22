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
import java.util.Locale;
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
        // Skip rather than failing startup; a call to a disabled instance fails
        // loudly on its own. The level differs because the two absences mean
        // opposite things.
        if (env == ApiInstanceEnv.PROD) {
          // Expected everywhere but the PROD deployment - a lower environment
          // holding no PROD endpoint is the point, not a misconfiguration.
          log.info("Forest Client API PROD instance not configured, which is "
              + "expected outside the PROD deployment.");
        } else {
          // Every environment uses the TEST instance, so this is always wrong.
          // Named after the variable that sets it, because the usual cause is
          // that the variable was never created.
          log.warn("Forest Client API TEST instance not configured: "
              + "FC_API_BASE_URL_TEST is blank, so forest-client search will fail "
              + "for every non-PROD application. Set the fc_api_base_url_test "
              + "repository variable.");
        }
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

  /**
   * Free-text lookup for the autocomplete: name or number, matched by substring.
   *
   * <p>{@code /api/clients/findByClientNumberOrName/{term}} is the only endpoint
   * that does what the picker needs. Its query is literally
   * {@code CLIENT_NUMBER LIKE %term% OR CLIENT_NAME LIKE %term%}, so one term
   * covers both fields and a partial number needs no zero-padding: "58846" is
   * contained in "00058846".
   *
   * <p>Not {@code /api/clients/search/by}, which was tried first and looks right
   * from its parameter list. Its name match is a Jaro-Winkler similarity at 80,
   * and its number match is {@code CLIENT_NUMBER = :number} - exact. That is why
   * "ser" answered with REYBURN and SWAIN while "000" answered with nothing.
   *
   * <p><b>The term is upper-cased</b> because this endpoint passes it to the
   * database untouched and the legacy client names are stored upper-case; a
   * lower-case term would match nothing. Digits are unaffected.
   *
   * <p>The term travels in the path, not the query string, so it is sent as a URI
   * variable and left for the builder to encode.
   *
   * <p>No retry: this runs on every keystroke pause, where latency matters more
   * than a second attempt.
   */
  public List<Map<String, Object>> searchByNumberOrName(
      String term, int size, ApiInstanceEnv apiInstanceEnv) {

    String needle = term.toUpperCase(Locale.ROOT);

    return get(apiInstanceEnv, uriBuilder -> uriBuilder
        .path("/api/clients/findByClientNumberOrName/{term}")
        .queryParam("page", FamConstants.DEFAULT_FC_API_SEARCH_PAGE)
        .queryParam("size", size)
        .build(needle));
  }

  /**
   * Look up an organisation by its acronym.
   *
   * <p>Its own call because no substring endpoint covers the acronym, and an
   * organisation people know by acronym is not findable by name - BCTS is not a
   * substring of "BC TIMBER SALES". The match is {@code CLIENT_ACRONYM = :acronym},
   * exact, so this only ever answers a fully-typed acronym.
   */
  public List<Map<String, Object>> searchByAcronym(
      String acronym, int size, ApiInstanceEnv apiInstanceEnv) {

    return get(apiInstanceEnv, uriBuilder -> uriBuilder
        .path("/api/clients/search/by")
        .queryParam("page", FamConstants.DEFAULT_FC_API_SEARCH_PAGE)
        .queryParam("size", size)
        .queryParam("acronym", acronym.toUpperCase(Locale.ROOT))
        .build());
  }

  /**
   * A GET returning a bare JSON array, with this API's error conventions applied.
   *
   * <p>404 and 400 mean "nothing matched" here rather than "failed" - the picker
   * searches by a free-text field that may be neither a name nor a number, and
   * the API answers a term it cannot parse with a status rather than an empty
   * list.
   */
  private List<Map<String, Object>> get(
      ApiInstanceEnv apiInstanceEnv,
      java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uri) {

    RestClient client = clientFor(apiInstanceEnv);

    try {
      ResponseEntity<byte[]> response = client.get().uri(uri).retrieve().toEntity(byte[].class);

      HttpStatus status = HttpStatus.valueOf(response.getStatusCode().value());
      if (status.isError()) {
        if (status == HttpStatus.NOT_FOUND || status == HttpStatus.BAD_REQUEST) {
          log.debug("Forest Client API returned {} for lookup; treating as no match",
              status.value());
          return List.of();
        }
        throw errorTranslator.httpError(
            UPSTREAM, response.getStatusCode(), response.getBody(), status.getReasonPhrase());
      }

      return parseBody(response.getBody());
    } catch (ResourceAccessException e) {
      log.error("Forest Client API lookup failed", e);
      throw errorTranslator.connectivityFailure(UPSTREAM, e);
    }
  }

  private List<Map<String, Object>> doSearch(
      List<String> forestClientNumbers, int size, ApiInstanceEnv apiInstanceEnv) {
    return doSearch(forestClientNumbers, null, size, apiInstanceEnv);
  }

  private List<Map<String, Object>> doSearch(
      List<String> forestClientNumbers, String name, int size, ApiInstanceEnv apiInstanceEnv) {

    RestClient client = clientFor(apiInstanceEnv);

    UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/api/clients/search")
        .queryParam("page", FamConstants.DEFAULT_FC_API_SEARCH_PAGE)
        .queryParam("size", size);
    // Repeated "id" parameters, one per client number: &id=00001011&id=00001012
    forestClientNumbers.forEach(number -> uri.queryParam("id", number));

    if (name != null && !name.isBlank()) {
      uri.queryParam("name", name);
    }

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
