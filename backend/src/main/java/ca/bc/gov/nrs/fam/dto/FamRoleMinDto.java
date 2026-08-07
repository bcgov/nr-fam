package ca.bc.gov.nrs.fam.dto;

/** A role reduced to what a nested reference needs. */
import io.swagger.v3.oas.annotations.media.Schema;

public record FamRoleMinDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleTypeCode,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FamApplicationDto application) {}
