package ca.bc.gov.nrs.fam.dto;

import java.util.List;

/**
 * Everything the signed-in admin is allowed to do.
 *
 * <p>The frontend drives its entire navigation and permission model from this
 * one response, so a capacity the user does not hold is absent rather than
 * present-and-empty.
 */
import io.swagger.v3.oas.annotations.media.Schema;

public record AdminUserAccessResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FamAuthGrantDto> access) {}
