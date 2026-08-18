package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsPermissionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * What was recorded when a role was removed, stored as the audit row's
 * {@code privilege_details}.
 *
 * <p>Follows {@link RoleDefinitionDetailsDto} in being a superset of
 * {@link PrivilegeDetailsDto}, for the same reason: the history reader parses
 * every row as that shape, and an audit document outlives the code that wrote
 * it. {@code permissionType} and {@code roles} are carried so an older reader
 * sees a coherent record naming the role.
 *
 * <p>The two fields unique to a deletion are the point of the row.
 * {@code membersAffected} is the number of people who lost the role, which
 * nothing else records - CSS keeps no trace of a role once it is gone, so after
 * this the assignments are unrecoverable. {@code removedRoles} names every CSS
 * role that went with it, since a scoped role takes its per-scope roles too.
 */
public record RoleDeletionDetailsDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    PrivilegeDetailsPermissionType permissionType,

    /** One entry naming the role, so the document reads as a privilege detail. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    List<PrivilegeDetailsRoleDto> roles,

    /** The raw code, which was also the CSS role name. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleCode,

    /** The role's short name as it read at deletion, or null if it had none. */
    String roleName,

    /** Every CSS role removed, the role itself included. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> removedRoles,

    /** How many people held the role when it was removed. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int membersAffected) {

  /** Builds the document, keeping the two representations of the code in step. */
  public static RoleDeletionDetailsDto of(
      String roleCode, String roleName, List<String> removedRoles, int membersAffected) {

    return new RoleDeletionDetailsDto(
        PrivilegeDetailsPermissionType.ROLE_DEFINITION,
        List.of(new PrivilegeDetailsRoleDto(roleCode, null, null)),
        roleCode,
        roleName,
        removedRoles,
        membersAffected);
  }
}
