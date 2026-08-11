package ca.bc.gov.nrs.fam.exception;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Port of {@code server/backend/api/app/exception_handlers.py}.
 *
 * <p>Three response shapes are preserved exactly, because the Vue frontend
 * branches on all three:
 *
 * <ul>
 *   <li>Business errors: {@code {"detail": {"code", "description"}}}
 *   <li>Validation errors: {@code {"detail": [{"loc", "msg", "type"}]}} - the
 *       same shape FastAPI's 422 handler produced. {@code utils/ApiUtils.ts}
 *       distinguishes the two by testing {@code Array.isArray(detail)}.
 *   <li>Upstream failures: {@code {"failureCode", "message"}}
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(FamHttpException.class)
  public ResponseEntity<Map<String, Object>> handleFamHttp(
      FamHttpException ex, HttpServletRequest request) {
    // The description says which claim, which client, which rule - the code alone
    // sends you back to the source to find out.
    log.debug("{} {} -> {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getStatus(),
        ex.getCode(), ex.getDescription());
    return ResponseEntity.status(ex.getStatus())
        .body(Map.of("detail", Map.of("code", ex.getCode(), "description", ex.getDescription())));
  }

  /**
   * Upstream timeouts and connectivity failures surface as HTTP 504; other
   * upstream statuses are relayed.
   *
   * <p>Upstream 401/403 are deliberately remapped to 500 by the throwing side so
   * the frontend does not mistake an integration credential problem for the
   * end user's own session expiring.
   */
  @ExceptionHandler(UpstreamException.class)
  public ResponseEntity<Map<String, Object>> handleUpstream(
      UpstreamException ex, HttpServletRequest request) {
    log.error("{} {} -> {} upstream={} <{}>", request.getMethod(), request.getRequestURI(),
        ex.getStatus(), ex.getUpstream(), ex.getMessage());

    Map<String, Object> body = new LinkedHashMap<>();
    // Upstream could legitimately return no failure code; the key is still
    // present in the payload, with a null value, as it was in Python.
    body.put("failureCode", ex.getFailureCode());
    body.put("message", ex.getMessage());
    return ResponseEntity.status(ex.getStatus()).body(body);
  }

  /** Request body / DTO validation. Mirrors FastAPI's 422 payload. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleBodyValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<Map<String, Object>> details = new ArrayList<>();
    ex.getBindingResult().getFieldErrors().forEach(fe ->
        details.add(validationDetail(List.of("body", fe.getField()), fe.getDefaultMessage(),
            fe.getCode())));
    ex.getBindingResult().getGlobalErrors().forEach(ge ->
        details.add(validationDetail(List.of("body", ge.getObjectName()), ge.getDefaultMessage(),
            ge.getCode())));

    log.error("Validation failed for {} {}: {}", request.getMethod(), request.getRequestURI(),
        details);
    return ResponseEntity.unprocessableEntity().body(Map.of("detail", details));
  }

  /** Path variable / request param validation. */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<Map<String, Object>> handleParamValidation(
      ConstraintViolationException ex, HttpServletRequest request) {
    List<Map<String, Object>> details = ex.getConstraintViolations().stream()
        .map(v -> validationDetail(
            List.of("query", v.getPropertyPath().toString()),
            v.getMessage(),
            v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()))
        .toList();

    log.error("Validation failed for {} {}: {}", request.getMethod(), request.getRequestURI(),
        details);
    return ResponseEntity.unprocessableEntity().body(Map.of("detail", details));
  }

  /**
   * Everything unhandled. Upstream returned the exception text as
   * {@code text/plain}; this returns the standard error object instead so the
   * response is always JSON, and the message is withheld to avoid leaking
   * internals.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleUnhandled(
      Exception ex, HttpServletRequest request) {
    log.error("{} {} -> 500 Internal Server Error <{}: {}>", request.getMethod(),
        request.getRequestURI(), ex.getClass().getSimpleName(), ex.getMessage(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("detail",
            Map.of("code", ErrorCode.UNKNOWN_STATE, "description", "Internal Server Error")));
  }

  private static Map<String, Object> validationDetail(List<String> loc, String msg, String type) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("loc", loc);
    detail.put("msg", msg == null ? "invalid value" : msg);
    detail.put("type", type == null ? "value_error" : type);
    return detail;
  }
}
