package ca.bc.gov.nrs.fam.constants;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonValue;

/** {@code enumAsRef} emits this as a named schema so the generated client keeps the name. */
@Schema(enumAsRef = true)
public enum PrivilegeDetailsPermissionType {
  END_USER("End User"),
  DELEGATED_ADMIN("Delegated Admin"),
  APPLICATION_ADMIN("Application Admin"),

  /**
   * Not a permission somebody holds, but the definition of one - see
   * {@link PrivilegeChangeType#CREATE_ROLE}.
   */
  ROLE_DEFINITION("Role Definition");

  private final String value;

  PrivilegeDetailsPermissionType(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }
}
