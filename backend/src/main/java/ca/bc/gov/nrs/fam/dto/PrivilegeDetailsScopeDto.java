package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsScopeType;

/**
 * The forest client a granted role was scoped to.
 *
 * <p>{@code roleAssignmentExpiryDate} is an ISO-8601 string rather than a date
 * type: it is persisted inside a JSON document and round-tripped verbatim.
 */

public record PrivilegeDetailsScopeDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PrivilegeDetailsScopeType scopeType,
    String clientId,
    String clientName,
    String roleAssignmentExpiryDate) {}
