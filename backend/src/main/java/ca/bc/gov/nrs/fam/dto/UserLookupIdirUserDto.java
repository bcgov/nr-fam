package ca.bc.gov.nrs.fam.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An IDIR user as nr-user-lookup-api returns them.
 *
 * <p>The naming strategy is pinned: the directory's wire format is camelCase and
 * FAM's global snake_case strategy would otherwise deserialise every multi-word
 * field to null.
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserLookupIdirUserDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean found,
    String userId,
    String guid,
    String firstName,
    String lastName,
    String email) {}
