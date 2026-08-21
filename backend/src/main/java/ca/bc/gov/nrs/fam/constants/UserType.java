package ca.bc.gov.nrs.fam.constants;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import java.util.Optional;

/**
 * Identity provider type for a FAM user.
 *
 * <p>Never stored on its own. It is the prefix on every identity the audit table
 * records - {@code create_user}, {@code update_user}, {@code performer_user} and
 * {@code target_user} - see {@link ca.bc.gov.nrs.fam.security.AuditUser}.
 *
 * <p>The code is the provider's own name rather than an abbreviation. It is read
 * by people diagnosing access problems, so {@code I} and {@code B} bought
 * nothing and cost legibility everywhere they appeared.
 *
 * <p><b>BC Services Card is not modelled.</b> FAM does not admit BCSC logins -
 * {@link ca.bc.gov.nrs.fam.security.IdentityProvider} rejects the claim - so a
 * BCSC user type could only ever describe a row that cannot be created. The
 * earlier enum carried three such codes and every switch over it needed a branch
 * for cases that were unreachable.
 *
 * <p>{@code enumAsRef} makes springdoc emit this as a named component schema
 * rather than inlining it into each property. Without it the generated
 * TypeScript client names the type after the property that uses it
 * ({@code FamAuthGrantDtoAuthKeyEnum}); the frontend imports these by name.
 */
@Schema(enumAsRef = true)
public enum UserType {
  IDIR("IDIR"),
  BCEID("BCEID_BUS");

  private final String code;

  UserType(String code) {
    this.code = code;
  }

  @JsonValue
  public String getCode() {
    return code;
  }

  /**
   * Look up by stored code.
   *
   * @return empty rather than throwing, so a row carrying a code this version
   *     does not know cannot fail the query that read it
   */
  public static Optional<UserType> fromCode(String code) {
    return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst();
  }
}
