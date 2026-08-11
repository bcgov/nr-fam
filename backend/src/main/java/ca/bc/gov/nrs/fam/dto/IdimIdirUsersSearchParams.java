package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.FamConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Query parameters for the IDIR user search.
 *
 * <p>Port of {@code IdimProxyIdirUsersSearchParamReqSchema}. Two rules from the
 * Pydantic validators are reproduced in {@link #validate()} because bean
 * validation cannot express them: at least one search field must be present, and
 * any field that is present must be at least two characters. Both exist to stop
 * a search so broad that IDIM returns an arbitrary slice of the directory.
 */
@Schema(name = "IdimIdirUsersSearchParams")
public class IdimIdirUsersSearchParams {

  private static final int MIN_SEARCH_TEXT_LENGTH = 2;

  @Size(max = FamConstants.EXT_MAX_FIRST_NAME_LEN)
  @Schema(description = "IDIR first name search value (min 2 chars)")
  private String firstName;

  @Size(max = FamConstants.EXT_MAX_LAST_NAME_LEN)
  @Schema(description = "IDIR last name search value (min 2 chars)")
  private String lastName;

  @Size(max = FamConstants.EXT_MAX_IDP_USERNAME_LEN)
  @Schema(description = "IDIR user id search value (min 2 chars)")
  private String userId;

  @Min(FamConstants.EXT_MIN_PAGE_SIZE)
  @Max(FamConstants.EXT_IDIM_SEARCH_MAX_PAGE_SIZE)
  @Schema(description = "Number of records to return")
  private int pageSize = FamConstants.EXT_DEFAULT_PAGE_SIZE;

  /** Blank input is treated as absent, matching the Pydantic {@code before} validator. */
  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = normalize(firstName);
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = normalize(lastName);
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = normalize(userId);
  }

  public int getPageSize() {
    return pageSize;
  }

  public void setPageSize(int pageSize) {
    this.pageSize = pageSize;
  }

  /**
   * @throws IllegalArgumentException if no field was supplied, or a supplied field
   *     is shorter than two characters. Callers translate this into the API's
   *     validation error shape.
   */
  public void validate() {
    if (firstName == null && lastName == null && userId == null) {
      throw new IllegalArgumentException(
          "At least one of firstName, lastName, or userId must be provided");
    }
    requireMinLength("firstName", firstName);
    requireMinLength("lastName", lastName);
    requireMinLength("userId", userId);
  }

  private static void requireMinLength(String field, String value) {
    if (value != null && value.length() < MIN_SEARCH_TEXT_LENGTH) {
      throw new IllegalArgumentException(
          field + " must be at least " + MIN_SEARCH_TEXT_LENGTH + " characters when provided");
    }
  }

  /**
   * Build the IDIM query string.
   *
   * <p>Names are matched with {@code Contains} so partial input works, while a
   * user id is matched {@code Exact} - a partial id is never useful and would
   * return noise. FAM does not expose these modes to callers.
   */
  public Map<String, Object> toQueryParams() {
    Map<String, Object> params = new LinkedHashMap<>();
    if (firstName != null) {
      params.put("firstName", firstName);
      params.put("firstNameMatchMode", "Contains");
    }
    if (lastName != null) {
      params.put("lastName", lastName);
      params.put("lastNameMatchMode", "Contains");
    }
    if (userId != null) {
      params.put("userId", userId);
      params.put("userIdMatchMode", "Exact");
    }
    params.put("pageSize", pageSize);
    return params;
  }
}
