package ca.bc.gov.nrs.fam.constants;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonValue;

/** {@code enumAsRef} emits this as a named schema so the generated client keeps the name. */
@Schema(enumAsRef = true)
public enum SortOrder {
  ASC("asc"),
  DESC("desc");

  private final String value;

  SortOrder(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }
}
