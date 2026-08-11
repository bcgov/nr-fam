package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.IdpType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** One user in an external user-search result. camelCase; see {@link ExtRoleWithScopeDto}. */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ExtApplicationUserSearchGetDto(
    String firstName,
    String lastName,
    String idpUsername,
    String idpUserGuid,
    // The record component cannot be named idPType, so the wire name is pinned.
    @JsonProperty("idpType") IdpType idpType,
    List<ExtRoleWithScopeDto> roles) {}
