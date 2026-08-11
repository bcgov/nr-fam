package ca.bc.gov.nrs.fam.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** One of the caller's own roles, with its expiry and forest client scope. */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ExtUserRoleMetadataRoleDto(
    String roleName,
    String displayName,
    /** Null means the assignment never expires. */
    OffsetDateTime expiryDate,
    /** Set only when the role is scoped to a forest client. */
    String forestClientNumber) {}
