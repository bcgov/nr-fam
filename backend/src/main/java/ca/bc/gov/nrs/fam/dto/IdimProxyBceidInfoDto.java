package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Business BCeID user details from the IDIM proxy.
 *
 * <p>camelCase for the same reason as {@link IdimProxyIdirInfoDto}.
 *
 * <p>{@code businessGuid} is what the same-organisation checks compare against.
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)

public record IdimProxyBceidInfoDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean found,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String userId,
    String guid,
    String businessGuid,
    String businessLegalName,
    String firstName,
    String lastName,
    String email) {}
