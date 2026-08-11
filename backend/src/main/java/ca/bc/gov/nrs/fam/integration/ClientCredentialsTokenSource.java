package ca.bc.gov.nrs.fam.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * The OAuth2 {@code client_credentials} exchange for an outbound integration.
 *
 * <p>Ported from {@code nr-fsp-new}'s {@code ClientCredentialsTokenSource}. Owns
 * its own {@link RestClient} pointed at the token endpoint, the Basic-auth header
 * derived from the client id and secret, and the form body.
 *
 * <p>Tokens are cached and refreshed about a minute before expiry, to absorb
 * clock skew and token-endpoint latency rather than discovering the expiry
 * mid-request.
 *
 * <p>Note this sends the credentials as a Basic header, where
 * {@link CssApiService} puts them in the form body. Both are permitted by the
 * spec; each matches what its upstream expects.
 */
@Slf4j
public final class ClientCredentialsTokenSource {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Refresh this far ahead of expiry. */
  private static final Duration EXPIRY_BUFFER = Duration.ofSeconds(60);

  /** Used when the token endpoint omits {@code expires_in}. */
  private static final long DEFAULT_TTL_SECONDS = 300L;

  private final RestClient http;
  private final String tokenUrl;
  private final String clientId;
  private final String scope;
  private final String basicAuthHeader;

  /**
   * Cached token and its expiry.
   *
   * <p>{@code volatile} because a scheduled job and a request thread may both
   * reach {@link #fetchCached()}; a rare double-fetch on a race is harmless,
   * where a lock on every call would not be free.
   */
  private volatile String cachedAccessToken = "";
  private volatile Instant cachedAccessTokenExpiresAt = Instant.EPOCH;

  private ClientCredentialsTokenSource(
      RestClient http, String tokenUrl, String clientId, String scope, String basicAuthHeader) {
    this.http = http;
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.scope = scope;
    this.basicAuthHeader = basicAuthHeader;
  }

  /**
   * @return a configured source when token url, client id and secret are all
   *     present; {@code null} when none of them are, which means the caller did
   *     not ask for authentication at all.
   * @throws IllegalStateException when only some are set. A half-configured
   *     integration would otherwise call out unauthenticated and read as "no
   *     results" rather than as a misconfiguration.
   */
  public static ClientCredentialsTokenSource fromProperties(
      String tokenUrl, String clientId, String clientSecret, String scope,
      Duration connectTimeout, Duration readTimeout, RestClientFactory restClientFactory) {

    String url = trim(tokenUrl);
    String id = trim(clientId);
    String secret = trim(clientSecret);
    String requestedScope = trim(scope);

    if (url.isBlank() && id.isBlank() && secret.isBlank()) {
      return null;
    }
    if (url.isBlank() || id.isBlank() || secret.isBlank()) {
      throw new IllegalStateException(
          "client_credentials is misconfigured: set all of token-url, client-id and "
              + "client-secret, or none of them.");
    }

    RestClient http = restClientFactory.create("", connectTimeout, readTimeout,
        headers -> headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON)));

    String basic = "Basic " + Base64.getEncoder().encodeToString(
        (id + ":" + secret).getBytes(StandardCharsets.UTF_8));

    return new ClientCredentialsTokenSource(http, url, id, requestedScope, basic);
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  public String tokenUrl() {
    return tokenUrl;
  }

  public String clientId() {
    return clientId;
  }

  public String scope() {
    return scope;
  }

  /**
   * A valid access token, fetched when the cache is empty or near expiry.
   *
   * <p>Returns {@code ""} rather than throwing when a fetch fails, leaving the
   * caller to decide what an unauthenticated call should mean. The next call
   * retries.
   */
  public String fetchCached() {
    Instant now = Instant.now();
    if (!cachedAccessToken.isBlank()
        && now.isBefore(cachedAccessTokenExpiresAt.minus(EXPIRY_BUFFER))) {
      return cachedAccessToken;
    }

    try {
      TokenResponse fresh = fetch();
      long ttl = fresh.expiresIn() == null || fresh.expiresIn() <= 0
          ? DEFAULT_TTL_SECONDS : fresh.expiresIn();

      cachedAccessToken = fresh.accessToken();
      cachedAccessTokenExpiresAt = now.plusSeconds(ttl);
      return cachedAccessToken;

    } catch (RuntimeException e) {
      log.warn("client_credentials token fetch failed ({}); the next call will retry",
          e.getMessage());
      cachedAccessToken = "";
      cachedAccessTokenExpiresAt = Instant.EPOCH;
      return "";
    }
  }

  TokenResponse fetch() {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    if (!scope.isBlank()) {
      form.add("scope", scope);
    }

    // The shared RestClient suppresses error statuses so each call site can read
    // the raw body, so the status has to be inspected here. Without this, a 401
    // from the token endpoint arrives as a body with no access_token and the
    // reason - "Client not enabled to retrieve service account", "invalid_client"
    // - is discarded, leaving nothing to act on.
    ResponseEntity<String> response = http.post()
        .uri(URI.create(tokenUrl))
        .header(HttpHeaders.AUTHORIZATION, basicAuthHeader)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(form)
        .retrieve()
        .toEntity(String.class);

    String body = response.getBody() == null ? "" : response.getBody();

    if (response.getStatusCode().isError()) {
      throw new IllegalStateException("Token endpoint returned HTTP %d: %s"
          .formatted(response.getStatusCode().value(), describe(body)));
    }

    TokenResponse parsed = parse(body);
    if (parsed == null || parsed.accessToken() == null || parsed.accessToken().isBlank()) {
      throw new IllegalStateException(
          "Token endpoint returned no access_token: " + describe(body));
    }
    return parsed;
  }

  /**
   * The endpoint's own error, when it gave one.
   *
   * <p>OAuth2 error responses carry {@code error} and {@code error_description},
   * which name the actual misconfiguration. Falls back to the raw body, truncated
   * so a stray HTML error page cannot flood the log.
   */
  private static String describe(String body) {
    try {
      TokenErrorResponse error = MAPPER.readValue(body, TokenErrorResponse.class);
      if (error != null && error.error() != null) {
        return error.errorDescription() == null
            ? error.error()
            : error.error() + " - " + error.errorDescription();
      }
    } catch (Exception ignored) {
      // Not an OAuth2 error document; fall through to the raw body.
    }
    String trimmed = body.strip();
    return trimmed.length() > 300 ? trimmed.substring(0, 300) + "..." : trimmed;
  }

  private static TokenResponse parse(String body) {
    try {
      return MAPPER.readValue(body, TokenResponse.class);
    } catch (Exception e) {
      return null;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record TokenErrorResponse(
      @JsonProperty("error") String error,
      @JsonProperty("error_description") String errorDescription) {}

  /**
   * Token endpoints return more than this - refresh_token, id_token, scope - so
   * unknown fields are ignored rather than failing the exchange.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TokenResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("expires_in") Long expiresIn,
      @JsonProperty("token_type") String tokenType) {}
}
