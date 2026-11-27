package ca.bc.gov.nrs.fam.integration;

import java.nio.charset.StandardCharsets;

/**
 * What an upstream response may be quoted as when it cannot be read.
 *
 * <p>These bodies are other people's data. A CSS user listing is full names,
 * usernames and work email addresses; a Forest Client response is client records.
 * Putting one in an exception message sent it two places at once - into the pod
 * log, and into the 502 the browser receives, because
 * {@code GlobalExceptionHandler} returns the message as {@code message}.
 *
 * <p>So the body stays out of the message. It is logged instead, at debug and
 * capped, which keeps it available to whoever is debugging a malformed response
 * and absent from an ordinary deployment - LOG_LEVEL is INFO.
 */
final class UpstreamBody {

  /**
   * Long enough to show a JSON opening and the shape of what followed, short
   * enough that an HTML error page cannot flood the log.
   */
  private static final int MAX_CHARS = 300;

  private UpstreamBody() {}

  static String preview(byte[] body) {
    return body == null || body.length == 0
        ? "<empty>"
        : preview(new String(body, StandardCharsets.UTF_8));
  }

  /** For a caller that has already decoded the body. */
  static String preview(String body) {
    if (body == null || body.isBlank()) {
      return "<empty>";
    }
    String text = body.strip();
    return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "..." : text;
  }
}
