package ca.bc.gov.nrs.fam.constants;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Scope a privilege applies to.
 *
 * <p>{@code DISTRICT} is not yet used. It is retained from upstream because the
 * OpenAPI generator mishandles a single-constant enum, and it is the planned
 * next scope type.
 *
 * <p>{@code enumAsRef} makes springdoc emit this as a named component schema
 * rather than inlining it into each property. Without it the generated
 * TypeScript client names the type after the property that uses it
 * ({@code FamAuthGrantDtoAuthKeyEnum}); the frontend imports these by name.
 */
@Schema(enumAsRef = true)
public enum PrivilegeDetailsScopeType {
  CLIENT("Client"),
  DISTRICT("District"),

  /**
   * Added when regions became a scope dimension.
   *
   * <p>Until then anything that was not a district was recorded as a client, so
   * a region-scoped grant went into the trail labelled as an organisation. Rows
   * written before this existed still say {@code Client} and cannot be corrected
   * - the trail is append-only, and the region code sits in the value either
   * way, so they read as an organisation with a region's name.
   */
  REGION("Region");

  private final String value;

  PrivilegeDetailsScopeType(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }
}
