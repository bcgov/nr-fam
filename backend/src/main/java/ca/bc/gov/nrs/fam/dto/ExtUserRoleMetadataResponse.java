package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.IdpType;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * The calling user's own roles within the application their token was issued for.
 *
 * <p>Lets a downstream application ask FAM what the signed-in user may do,
 * without needing FAM's roles in its own token.
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ExtUserRoleMetadataResponse(
    String userName, IdpType domain, List<ExtUserRoleMetadataRoleDto> roles) {}
