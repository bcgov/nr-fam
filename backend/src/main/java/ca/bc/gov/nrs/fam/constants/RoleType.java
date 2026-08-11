package ca.bc.gov.nrs.fam.constants;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/** {@code app_fam.fam_role.role_type_code}. *
 * <p>{@code enumAsRef} makes springdoc emit this as a named component schema
 * rather than inlining it into each property. Without it the generated
 * TypeScript client names the type after the property that uses it
 * ({@code FamAuthGrantDtoAuthKeyEnum}); the frontend imports these by name.
 */
@Schema(enumAsRef = true)
public enum RoleType {
  /** Parent role; grants are made against a concrete child scoped to a client. */
  ABSTRACT("A"),
  CONCRETE("C");

  private final String code;

  RoleType(String code) {
    this.code = code;
  }

  @JsonValue
  public String getCode() {
    return code;
  }

  public static Optional<RoleType> fromCode(String code) {
    return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst();
  }
}
