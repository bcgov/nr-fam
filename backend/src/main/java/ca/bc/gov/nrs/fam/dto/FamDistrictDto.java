package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.District;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A BC natural resource district, used to scope roles marked district-scoped.
 *
 * <p>Upstream's schema mixed casing - {@code org_unit_code} alongside
 * {@code orgUnitName} and {@code isExpired} - because it mirrored the shape of
 * the org unit source data. These are hardcoded constants rather than
 * passed-through data, so that justification does not carry over, and the mixed
 * casing would be the only place this API is not snake_case. Serialised as
 * {@code org_unit_code}, {@code org_unit_name}, {@code expired}.
 */
public record FamDistrictDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String orgUnitCode,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String orgUnitName,
    /** A dissolved or renamed district: still resolvable, but not grantable. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean expired) {

  public static FamDistrictDto from(District district) {
    return new FamDistrictDto(
        district.getOrgUnitCode(), district.getOrgUnitName(), district.isExpired());
  }
}
