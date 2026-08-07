package ca.bc.gov.nrs.fam.dto;

/** Minimal application view embedded in role responses. */
import io.swagger.v3.oas.annotations.media.Schema;

public record FamApplicationDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long applicationId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String applicationName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String applicationDescription) {}
