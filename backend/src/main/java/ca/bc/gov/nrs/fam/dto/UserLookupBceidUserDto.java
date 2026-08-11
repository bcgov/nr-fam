package ca.bc.gov.nrs.fam.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A Business BCeID user as nr-user-lookup-api returns them.
 *
 * <p>{@code businessGuid} is what the same-organisation rule compares against -
 * the directory returns it, but does not enforce anything with it.
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserLookupBceidUserDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean found,
    String userId,
    String guid,
    String businessGuid,
    String businessLegalName,
    String firstName,
    String lastName,
    String email) {}
