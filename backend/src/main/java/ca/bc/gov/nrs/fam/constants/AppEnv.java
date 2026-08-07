package ca.bc.gov.nrs.fam.constants;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** {@code app_fam.fam_application.app_environment}. *
 * <p>{@code enumAsRef} makes springdoc emit this as a named component schema
 * rather than inlining it into each property. Without it the generated
 * TypeScript client names the type after the property that uses it
 * ({@code FamAuthGrantDtoAuthKeyEnum}); the frontend imports these by name.
 */
@Schema(enumAsRef = true)
public enum AppEnv {
  DEV("DEV"),
  TEST("TEST"),
  PROD("PROD");

  private final String code;

  AppEnv(String code) {
    this.code = code;
  }

  @JsonValue
  public String getCode() {
    return code;
  }

  /**
   * Application environments in which an app admin may grant or revoke their own
   * role access.
   *
   * <p>Deliberately an allowlist rather than a {@code != PROD} check, so an
   * application with a missing or unrecognised {@code app_environment} fails
   * closed.
   */
  public static final Set<AppEnv> SELF_GRANT_ALLOWED = EnumSet.of(DEV, TEST);

  public static Optional<AppEnv> fromCode(String code) {
    return Arrays.stream(values()).filter(e -> e.code.equals(code)).findFirst();
  }
}
