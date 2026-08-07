package ca.bc.gov.nrs.fam.integration;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/**
 * Turns an outbound call failure into an {@link UpstreamException} carrying the
 * {@code failureCode}/{@code message} payload the frontend expects.
 *
 * <p>Port of {@code requests_http_error_handler} and
 * {@code requests_gateway_timeout_error_handler} from
 * {@code exception_handlers.py}. Those were global FastAPI handlers keyed on
 * {@code requests} exception types; here the translation happens at the call
 * site, where the upstream service is known.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpstreamErrorTranslator {

  private final ObjectMapper objectMapper;

  /**
   * A connectivity or timeout failure reaching the upstream service.
   *
   * <p>Both become HTTP 504, distinguished only by the failure code, exactly as
   * upstream did.
   */
  public UpstreamException connectivityFailure(String upstream, Throwable cause) {
    boolean timeout = isTimeout(cause);
    return new UpstreamException(
        HttpStatus.GATEWAY_TIMEOUT,
        timeout ? ErrorCode.UPSTREAM_TIMEOUT : ErrorCode.UPSTREAM_CONNECTION_ERROR,
        timeout ? "Upstream service timed out." : "Could not connect to upstream service.",
        upstream,
        cause);
  }

  /** Distinguishes a read timeout from a refused or unresolvable connection. */
  public static boolean isTimeout(Throwable cause) {
    for (Throwable t = cause; t != null; t = t.getCause()) {
      if (t instanceof SocketTimeoutException
          || t instanceof java.net.http.HttpTimeoutException) {
        return true;
      }
      if (t instanceof ConnectException || t instanceof UnknownHostException) {
        return false;
      }
    }
    // A ResourceAccessException with no recognisable cause is most often a read
    // timeout; upstream treated the ambiguous case the same way.
    return cause instanceof ResourceAccessException;
  }

  /**
   * An HTTP error response from the upstream service.
   *
   * <p>Two behaviours are carried over deliberately:
   *
   * <ul>
   *   <li>Upstream 401/403 are reported to our caller as 500. A credential problem
   *       between FAM and an integration is not the end user's session expiring,
   *       and the frontend would otherwise log them out.
   *   <li>The error payload is read from several shapes - FAM's own
   *       {@code failureCode}/{@code message}, GC Notify's
   *       {@code errors[0].error}/{@code errors[0].message}, or failing both, the
   *       raw body.
   * </ul>
   */
  public UpstreamException httpError(
      String upstream, HttpStatusCode upstreamStatus, byte[] body, String reasonPhrase) {

    String rawBody = body == null ? "" : new String(body, StandardCharsets.UTF_8);
    JsonNode json = parseOrNull(rawBody);

    String failureCode = extractFailureCode(json);
    String message = extractMessage(json, rawBody, reasonPhrase);

    HttpStatus responseStatus =
        (upstreamStatus.value() == HttpStatus.UNAUTHORIZED.value()
            || upstreamStatus.value() == HttpStatus.FORBIDDEN.value())
            ? HttpStatus.INTERNAL_SERVER_ERROR
            : HttpStatus.valueOf(upstreamStatus.value());

    log.error("Upstream {} returned {} ({}); reporting {} to caller",
        upstream, upstreamStatus.value(), failureCode, responseStatus.value());

    return new UpstreamException(responseStatus, failureCode, message, upstream);
  }

  private JsonNode parseOrNull(String rawBody) {
    if (rawBody == null || rawBody.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readTree(rawBody);
    } catch (IOException e) {
      // A non-JSON body is expected from some upstreams; fall back to raw text.
      return null;
    }
  }

  private static String extractFailureCode(JsonNode json) {
    if (json == null) {
      return null;
    }
    if (json.hasNonNull("failureCode")) {
      return json.get("failureCode").asText();
    }
    JsonNode firstError = firstError(json);
    if (firstError != null && firstError.hasNonNull("error")) {
      return firstError.get("error").asText();
    }
    return null;
  }

  private static String extractMessage(JsonNode json, String rawBody, String reasonPhrase) {
    if (json != null) {
      if (json.hasNonNull("message")) {
        return json.get("message").asText();
      }
      JsonNode firstError = firstError(json);
      if (firstError != null && firstError.hasNonNull("message")) {
        return firstError.get("message").asText();
      }
    }
    return (rawBody == null || rawBody.isBlank()) ? reasonPhrase : rawBody;
  }

  /** GC Notify reports problems as {@code {"errors": [{"error": ..., "message": ...}]}}. */
  private static JsonNode firstError(JsonNode json) {
    JsonNode errors = json.get("errors");
    return (errors != null && errors.isArray() && !errors.isEmpty()) ? errors.get(0) : null;
  }
}
