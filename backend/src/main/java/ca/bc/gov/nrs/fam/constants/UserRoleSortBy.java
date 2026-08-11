package ca.bc.gov.nrs.fam.constants;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Sortable columns for user-role listings.
 *
 * <p>These are API-level names, not entity property paths; the repository layer
 * maps them onto columns. {@code FULL_NAME} is a special case spanning
 * {@code first_name} and {@code last_name}.
 *
 * <p>{@code constants.py} declared {@code UserRoleSortByEnum} twice; the second
 * declaration wins at runtime in Python, so this port follows that one -
 * {@code CREATE_DATE} included, and first, as the default sort field.
 *
 * <p>{@code enumAsRef} makes springdoc emit this as a named component schema
 * rather than inlining it into each property. Without it the generated
 * TypeScript client names the type after the property that uses it
 * ({@code FamAuthGrantDtoAuthKeyEnum}); the frontend imports these by name.
 */
@Schema(enumAsRef = true)
public enum UserRoleSortBy {
  CREATE_DATE("create_date"),
  USER_NAME("user_name"),
  DOMAIN("user_type_code"),
  EMAIL("email"),
  FULL_NAME("full_name"),
  ROLE_DISPLAY_NAME("role_display_name"),
  FOREST_CLIENT_NUMBER("forest_client_number");

  private final String value;

  UserRoleSortBy(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  public static UserRoleSortBy defaultSort() {
    return CREATE_DATE;
  }
}
