package ca.bc.gov.nrs.fam.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * A failure calling an external service (Forest Client API, IDIM proxy, GC
 * Notify), serialised as {@code {"failureCode", "message"}}.
 *
 * <p>Replaces upstream's handling of {@code requests} exceptions in
 * {@code server/backend/api/app/exception_handlers.py}: {@code Timeout} and
 * {@code ConnectionError} became HTTP 504, and {@code HTTPError} was relayed
 * with the upstream status.
 */
@Getter
public class UpstreamException extends RuntimeException {

  /** Status to return to our caller. Not necessarily the upstream status. */
  private final HttpStatus status;

  private final String failureCode;

  /** The service that failed, for logging only; never returned to the caller. */
  private final String upstream;

  public UpstreamException(
      HttpStatus status, String failureCode, String message, String upstream, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.failureCode = failureCode;
    this.upstream = upstream;
  }

  public UpstreamException(HttpStatus status, String failureCode, String message, String upstream) {
    this(status, failureCode, message, upstream, null);
  }
}
