package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A role together with the forest client it is scoped to, if any.
 *
 * <p>Flattened rather than extending {@link FamRoleMinDto} because records cannot
 * inherit. Field names follow upstream's serialisation aliases:
 * {@code role_purpose} is exposed as {@code description}, and
 * {@code forest_client_relation} as {@code forest_client}.
 */

public record FamRoleWithClientDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long roleId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleTypeCode,
    String displayName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @JsonProperty("description") String description,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FamApplicationDto application,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @JsonProperty("forest_client") FamForestClientDto forestClient,
    FamRoleMinDto parentRole) {}
