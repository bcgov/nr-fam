package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.FamConstants;
import java.util.List;
import java.util.Map;

/**
 * Checks on a Forest Client API search result.
 *
 * <p>Port of {@code crud/validator/forest_client_validator.py}. FAM only ever
 * grants against an exact, single match: more than one result means the search
 * was not exact and is treated as no match.
 */
public final class ForestClientValidator {

  private ForestClientValidator() {}

  /** Exactly one result. Zero or several both mean "not a usable match". */
  public static boolean numberExists(List<Map<String, Object>> searchResult) {
    return searchResult != null && searchResult.size() == 1;
  }

  /** Whether the single matched client is active. Access is never granted otherwise. */
  public static boolean isActive(List<Map<String, Object>> searchResult) {
    if (!numberExists(searchResult)) {
      return false;
    }
    return FamConstants.FOREST_CLIENT_STATUS_CODE_ACTIVE.equals(status(searchResult));
  }

  /** The raw upstream status code, for the error message. Null when there is no match. */
  public static String status(List<Map<String, Object>> searchResult) {
    if (!numberExists(searchResult)) {
      return null;
    }
    Object statusCode = searchResult.get(0).get(FamConstants.FOREST_CLIENT_STATUS_KEY);
    return statusCode == null ? null : String.valueOf(statusCode);
  }

  public static String clientName(List<Map<String, Object>> searchResult) {
    if (!numberExists(searchResult)) {
      return null;
    }
    Object name = searchResult.get(0).get("clientName");
    return name == null ? null : String.valueOf(name);
  }
}
