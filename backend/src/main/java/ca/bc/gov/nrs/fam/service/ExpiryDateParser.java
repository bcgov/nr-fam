package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

/**
 * Converts the {@code expiry_date_date} request field into a stored instant.
 *
 * <p>Port of the {@code expiry_date_date} validators on
 * {@code FamUserRoleAssignmentCreateSchema}. Everything here is anchored to BC
 * local time, not the server's zone: an administrator in Victoria typing
 * "2026-08-31" means the end of that day where they are, and the pod's clock is
 * UTC.
 */
@Component
public class ExpiryDateParser {

  /** Matches {@code BC_TIMEZONE} in {@code datetime_format.py}. */
  public static final ZoneId BC_TIMEZONE = ZoneId.of("America/Vancouver");

  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

  /**
   * @param expiryDateDate {@code YYYY-MM-DD}, or null/blank for no expiry
   * @return the last instant of that day in BC time, or null
   * @throws FamHttpException if the value is unparseable or already past
   */
  public OffsetDateTime parse(String expiryDateDate) {
    if (expiryDateDate == null || expiryDateDate.isBlank()) {
      return null;
    }

    LocalDate date;
    try {
      date = LocalDate.parse(expiryDateDate.trim(), DATE_FORMAT);
    } catch (DateTimeParseException e) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "expiry_date_date must be a valid YYYY-MM-DD string");
    }

    // "Today" is today in BC, so an administrator can always grant access that
    // expires at the end of their current day.
    LocalDate todayInBc = LocalDate.now(BC_TIMEZONE);
    if (date.isBefore(todayInBc)) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "expiry_date_date must be today or in the future (BC timezone)");
    }

    // End of day, so access lasts through the date the requester chose.
    ZonedDateTime endOfDay = ZonedDateTime.of(date, LocalTime.of(23, 59, 59), BC_TIMEZONE);
    return endOfDay.toOffsetDateTime();
  }
}
