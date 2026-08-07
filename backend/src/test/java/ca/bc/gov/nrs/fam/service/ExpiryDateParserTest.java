package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ExpiryDateParser (port of the expiry_date_date validators)")
class ExpiryDateParserTest {

  private final ExpiryDateParser parser = new ExpiryDateParser();

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  @DisplayName("no expiry date means the assignment never expires")
  void blankMeansNoExpiry(String input) {
    assertThat(parser.parse(input)).isNull();
  }

  @Test
  @DisplayName("expires at the end of the chosen day, in BC time")
  void expiresAtEndOfDayInBc() {
    // The pod runs in UTC, but an administrator in Victoria choosing a date means
    // the end of that day where they are.
    LocalDate tomorrowInBc = LocalDate.now(ExpiryDateParser.BC_TIMEZONE).plusDays(1);

    OffsetDateTime expiry = parser.parse(tomorrowInBc.toString());

    assertThat(expiry).isNotNull();
    assertThat(expiry.atZoneSameInstant(ExpiryDateParser.BC_TIMEZONE).toLocalDate())
        .isEqualTo(tomorrowInBc);
    assertThat(expiry.atZoneSameInstant(ExpiryDateParser.BC_TIMEZONE).toLocalTime().toString())
        .isEqualTo("23:59:59");
  }

  @Test
  @DisplayName("today is allowed, so access can be granted for the rest of the day")
  void todayIsAllowed() {
    LocalDate todayInBc = LocalDate.now(ExpiryDateParser.BC_TIMEZONE);

    assertThat(parser.parse(todayInBc.toString())).isNotNull();
  }

  @Test
  @DisplayName("rejects a date already past in BC time")
  void rejectsPastDate() {
    LocalDate yesterdayInBc = LocalDate.now(ExpiryDateParser.BC_TIMEZONE).minusDays(1);

    assertThatThrownBy(() -> parser.parse(yesterdayInBc.toString()))
        .isInstanceOf(FamHttpException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER);
  }

  @ParameterizedTest
  @ValueSource(strings = {"31-08-2026", "2026/08/31", "not-a-date", "20260831"})
  @DisplayName("rejects anything that is not YYYY-MM-DD")
  void rejectsMalformedDate(String input) {
    assertThatThrownBy(() -> parser.parse(input))
        .isInstanceOf(FamHttpException.class)
        .hasMessageContaining("valid YYYY-MM-DD string");
  }

  @Test
  @DisplayName("tolerates surrounding whitespace")
  void trimsInput() {
    LocalDate tomorrowInBc = LocalDate.now(ExpiryDateParser.BC_TIMEZONE).plusDays(1);

    assertThat(parser.parse("  " + tomorrowInBc + "  ")).isNotNull();
  }
}
