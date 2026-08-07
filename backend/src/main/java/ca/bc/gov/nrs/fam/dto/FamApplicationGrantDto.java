package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.AppEnv;

/**
 * An application inside an access grant.
 *
 * <p>Deliberately renamed rather than reusing {@link FamApplicationDto}: the
 * admin UI consumes {@code id}/{@code name}/{@code description}/{@code env},
 * and {@code name} has its environment suffix stripped (FOM_DEV becomes FOM)
 * because the environment is carried separately in {@code env}.
 */
import io.swagger.v3.oas.annotations.media.Schema;

public record FamApplicationGrantDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
    String description,
    AppEnv env) {}
