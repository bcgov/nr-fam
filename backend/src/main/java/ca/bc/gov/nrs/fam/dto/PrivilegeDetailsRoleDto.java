package ca.bc.gov.nrs.fam.dto;

import java.util.List;

/**
 * A role involved in a privilege change.
 *
 * <p>{@code roleAssignmentExpiryDate} here applies to a role granted without
 * scopes; when scopes are present each scope carries its own expiry.
 */
import io.swagger.v3.oas.annotations.media.Schema;

public record PrivilegeDetailsRoleDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String role,
    List<PrivilegeDetailsScopeDto> scopes,
    String roleAssignmentExpiryDate) {}
