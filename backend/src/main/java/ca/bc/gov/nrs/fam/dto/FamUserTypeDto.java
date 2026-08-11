package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.UserType;
import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Identity provider of a user.
 *
 * <p>The code is serialised as {@code code}, not {@code user_type_code} - upstream
 * aliased it and the frontend reads {@code user_type.code}.
 */

public record FamUserTypeDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @JsonProperty("code") UserType code,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description) {}
