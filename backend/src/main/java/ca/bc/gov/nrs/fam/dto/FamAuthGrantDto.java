package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import java.util.List;

/** Everything a user may do in one administrative capacity. */
import io.swagger.v3.oas.annotations.media.Schema;

public record FamAuthGrantDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AdminRoleAuthGroup authKey,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FamGrantDetailDto> grants) {}
