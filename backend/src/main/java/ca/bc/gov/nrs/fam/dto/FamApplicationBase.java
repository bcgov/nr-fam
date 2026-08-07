package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ca.bc.gov.nrs.fam.constants.AppEnv;

/**
 * An application as the admin surface reports it.
 *
 * <p>Differs from {@link FamApplicationDto} only by carrying
 * {@code app_environment}, which the admin screens display.
 */

public record FamApplicationBase(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long applicationId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String applicationName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String applicationDescription,
    AppEnv appEnvironment) {}
