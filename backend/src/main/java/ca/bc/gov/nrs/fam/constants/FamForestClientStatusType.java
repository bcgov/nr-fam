package ca.bc.gov.nrs.fam.constants;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * FAM's internal client status, mapped from the Forest Client API's
 * {@code clientStatusCode}.
 *
 * <p>{@code enumAsRef} makes springdoc emit this as a named component schema
 * rather than inlining it into each property. Without it the generated
 * TypeScript client names the type after the property that uses it
 * ({@code FamAuthGrantDtoAuthKeyEnum}); the frontend imports these by name.
 */
@Schema(enumAsRef = true)
public enum FamForestClientStatusType {
  ACTIVE("A"),
  INACTIVE("I");

  private final String code;

  FamForestClientStatusType(String code) {
    this.code = code;
  }

  @JsonValue
  public String getCode() {
    return code;
  }
}
