package ca.bc.gov.nrs.fam.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;

/** One hit from an IDIR user search. camelCase, as the IDIM proxy returns it. */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record IdimProxyIdirUserSearchItemDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String userId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String guid,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String firstName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String lastName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email) {}
