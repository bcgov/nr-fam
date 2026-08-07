package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.constants.FamForestClientStatusType;

/**
 * FAM's Active/Inactive view of a forest client.
 *
 * <p>Not a FAM entity - it maps the Forest Client API's {@code clientStatusCode}
 * onto the two states FAM cares about.
 */
public record FamForestClientStatusDto(
    FamForestClientStatusType statusCode, String description) {

  /** Anything other than the API's active code is treated as inactive. */
  public static FamForestClientStatusDto fromApiStatusCode(String apiStatusCode) {
    boolean active = FamConstants.FOREST_CLIENT_STATUS_CODE_ACTIVE.equals(apiStatusCode);
    return active
        ? new FamForestClientStatusDto(
            FamForestClientStatusType.ACTIVE, FamConstants.DESCRIPTION_ACTIVE)
        : new FamForestClientStatusDto(
            FamForestClientStatusType.INACTIVE, FamConstants.DESCRIPTION_INACTIVE);
  }
}
