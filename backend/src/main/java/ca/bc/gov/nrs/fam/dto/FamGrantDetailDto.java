package ca.bc.gov.nrs.fam.dto;

import java.util.List;

/**
 * One application and the roles the user may grant within it.
 *
 * <p>{@code roles} is null for a FAM_ADMIN grant: that authority is over the
 * application as a whole, not over particular roles.
 */
import io.swagger.v3.oas.annotations.media.Schema;

public record FamGrantDetailDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FamApplicationGrantDto application,
    List<FamRoleGrantDto> roles) {}
