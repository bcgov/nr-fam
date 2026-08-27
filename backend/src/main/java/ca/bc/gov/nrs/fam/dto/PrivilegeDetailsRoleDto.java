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
    String roleAssignmentExpiryDate,

    /**
     * What the role is called, where FAM can still find out.
     *
     * <p>Filled in when the history is read rather than when the change is
     * written, and deliberately: resolving it at write time would cost a CSS
     * call on every audit row, where reading it costs one call for a whole
     * page of history.
     *
     * <p>The consequence is that a role renamed since the change shows its
     * <em>current</em> name. For a display name that is the better of the two
     * - people recognise what the role is called now - and the code beside it
     * in {@code role} is the part that has to stay true, which it does.
     *
     * <p>Null for a role that has since been deleted, or one that never had a
     * label sidecar. The code is what is shown then.
     */
    String roleDisplayName) {}
