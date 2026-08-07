package ca.bc.gov.nrs.fam.exception;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * A business-rule failure, serialised as {@code {"detail": {"code", "description"}}}.
 *
 * <p>Port of {@code utils.raise_http_exception} from
 * {@code server/backend/api/app/utils/utils.py}. The frontend matches on
 * {@code detail.code} (see {@code frontend/src/constants/ApiErrorCodes.ts} and
 * {@code utils/ApiUtils.ts}), so the response shape and the code values are part
 * of the contract.
 */
@Getter
public class FamHttpException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final String description;

  public FamHttpException(HttpStatus status, String code, String description) {
    super(description);
    this.status = status;
    this.code = code;
    this.description = description;
  }

  /** Matches the Python defaults: HTTP 400 with {@code invalid_operation}. */
  public static FamHttpException badRequest(String description) {
    return new FamHttpException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_OPERATION, description);
  }

  public static FamHttpException badRequest(String code, String description) {
    return new FamHttpException(HttpStatus.BAD_REQUEST, code, description);
  }

  public static FamHttpException forbidden(String code, String description) {
    return new FamHttpException(HttpStatus.FORBIDDEN, code, description);
  }

  public static FamHttpException notFound(String code, String description) {
    return new FamHttpException(HttpStatus.NOT_FOUND, code, description);
  }

  public static FamHttpException conflict(String code, String description) {
    return new FamHttpException(HttpStatus.CONFLICT, code, description);
  }

  public static FamHttpException internalError(String code, String description) {
    return new FamHttpException(HttpStatus.INTERNAL_SERVER_ERROR, code, description);
  }
}
