package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.Region;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A BC natural resource region, used to scope roles marked region-scoped.
 *
 * <p>Shaped like {@link FamDistrictDto} rather than reusing it: the two are
 * separate scope dimensions and a role may require either or both, so a shared
 * type would make the pickers and the request bodies ambiguous about which
 * dimension a value belongs to.
 */
public record FamRegionDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String regionCode,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String regionName,
    /** A dissolved or renamed region: still resolvable, but not grantable. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean expired) {

  public static FamRegionDto from(Region region) {
    return new FamRegionDto(region.getRegionCode(), region.getRegionName(), region.isExpired());
  }
}
