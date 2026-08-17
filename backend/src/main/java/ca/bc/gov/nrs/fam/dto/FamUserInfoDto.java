package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * User details safe to return to an admin.
 *
 * <p>Deliberately omits {@code user_guid}, {@code business_guid},
 * {@code oidc_user_id} and the audit columns, matching upstream's
 * {@code FamUserInfoSchema} exclusions.
 *
 * <p>The user-type object is serialised as {@code user_type} even though the
 * entity relation is named differently.
 */

public record FamUserInfoDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String userName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @JsonProperty("user_type") FamUserTypeDto userType,
    String firstName,
    String lastName,
    String email) {}
