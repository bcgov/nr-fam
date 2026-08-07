package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * IDIR user details from the IDIM proxy.
 *
 * <p>Explicitly camelCase, overriding the application-wide snake_case strategy.
 * The IDIM proxy speaks camelCase, and upstream passed its payload straight
 * through to the frontend, so both the inbound and outbound shapes must keep it.
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)

public record IdimProxyIdirInfoDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean found,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String userId,
    String guid,
    String firstName,
    String lastName,
    String email) {}
