package ca.bc.gov.nrs.fam.constants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Reading a user type off a query string.
 *
 * <p>The case this exists for: {@code BCEID} is published as {@code BCEID_BUS},
 * and Spring's default enum conversion matches constant names, not wire codes.
 * {@code IDIR} hid the problem by having the same string for both.
 */
@DisplayName("UserTypeConverter (query-string user types)")
class UserTypeConverterTest {

  private final UserTypeConverter converter = new UserTypeConverter();

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "BCEID_BUS, BCEID",
      "IDIR,      IDIR",
  })
  @DisplayName("reads the wire code the API publishes and the client sends")
  void readsTheWireCode(String value, UserType expected) {
    assertThat(converter.convert(value)).isEqualTo(expected);
  }

  @Test
  @DisplayName("reads the constant name too, for a URL written by hand")
  void readsTheConstantName() {
    // Nothing FAM generates sends this, but it has one meaning and refusing it
    // would be pedantry.
    assertThat(converter.convert("BCEID")).isEqualTo(UserType.BCEID);
    assertThat(converter.convert("bceid")).isEqualTo(UserType.BCEID);
  }

  @Test
  @DisplayName("ignores surrounding space")
  void ignoresSpace() {
    assertThat(converter.convert("  BCEID_BUS ")).isEqualTo(UserType.BCEID);
  }

  @Test
  @DisplayName("names the value and what was expected when it cannot be read")
  void namesTheBadValue() {
    // Read back to the caller as a 422 - see GlobalExceptionHandler - so the
    // message has to say which value was wrong and what would have been right.
    assertThatThrownBy(() -> converter.convert("BCSC"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BCSC")
        .hasMessageContaining("BCEID_BUS");
  }
}
