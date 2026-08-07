package ca.bc.gov.nrs.fam.constants;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/**
 * Identity provider type for a FAM user, stored as
 * {@code app_fam.fam_user.user_type_code}.
 *
 * <p>Upstream's Python enum listed only IDIR and BCEID even though V27 added the
 * three BC Services Card codes to {@code fam_user_type_code}. All five are
 * modelled here so that reading a BCSC user row cannot fail; look-ups return an
 * {@link Optional} rather than throwing on an unrecognised code.
 *
 * <p>{@code enumAsRef} makes springdoc emit this as a named component schema
 * rather than inlining it into each property. Without it the generated
 * TypeScript client names the type after the property that uses it
 * ({@code FamAuthGrantDtoAuthKeyEnum}); the frontend imports these by name.
 */
@Schema(enumAsRef = true)
public enum UserType {
  IDIR("I"),
  BCEID("B"),
  BCSC_DEV("CD"),
  BCSC_TEST("CT"),
  BCSC_PROD("CP");

  private final String code;

  UserType(String code) {
    this.code = code;
  }

  @JsonValue
  public String getCode() {
    return code;
  }

  public boolean isBcsc() {
    return this == BCSC_DEV || this == BCSC_TEST || this == BCSC_PROD;
  }

  public static Optional<UserType> fromCode(String code) {
    return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst();
  }
}
