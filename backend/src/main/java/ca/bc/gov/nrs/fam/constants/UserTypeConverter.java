package ca.bc.gov.nrs.fam.constants;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Reads {@link UserType} out of a query string by its wire code.
 *
 * <p>Spring's default enum conversion is {@code Enum.valueOf}, which matches the
 * <em>constant name</em>. {@code UserType} does not use its constant names on
 * the wire: {@code BCEID} is published as {@code BCEID_BUS} through
 * {@code @JsonValue}, which is what the OpenAPI schema and the generated client
 * send. A request body was fine, because Jackson honours that annotation; a
 * query parameter was not, because this conversion does not go through Jackson.
 *
 * <p>So {@code ?targetUserType=BCEID_BUS} failed with "No enum constant
 * UserType.BCEID_BUS" and a 500. {@code IDIR} worked by coincidence - its code
 * and its constant name are the same string - which is why only BCeID users
 * tripped it, and why it survived until somebody opened a BCeID user's history.
 */
@Component
public class UserTypeConverter implements Converter<String, UserType> {

  @Override
  public UserType convert(String source) {
    String value = source.trim();
    return UserType.fromCode(value)
        // The constant name too, so a hand-written URL saying BCEID rather than
        // BCEID_BUS is read rather than refused. Nothing FAM generates sends it,
        // but refusing it would be pedantry about a value with one meaning.
        .or(() -> java.util.Arrays.stream(UserType.values())
            .filter(type -> type.name().equalsIgnoreCase(value))
            .findFirst())
        .orElseThrow(() -> new IllegalArgumentException(
            "Unknown user type '%s'. Expected one of %s.".formatted(
                source,
                java.util.Arrays.stream(UserType.values())
                    .map(UserType::getCode).toList())));
  }
}
