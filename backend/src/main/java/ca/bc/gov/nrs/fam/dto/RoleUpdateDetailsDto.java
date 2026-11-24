package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsPermissionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Audit details for an UPDATE_ROLE row: the role, and its text before and after.
 *
 * <p>Before as well as after, because CSS keeps no history: once the old sidecar
 * is deleted, this row is the only record of what the role used to be called.
 *
 * <p>Carries {@code roles} like every other details document, so readers that
 * look for the role a row is about - the users listing's cache invalidation
 * among them - find it in the usual place.
 */
public record RoleUpdateDetailsDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    PrivilegeDetailsPermissionType permissionType,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    List<PrivilegeDetailsRoleDto> roles,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleCode,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleName,
    String description,
    String previousRoleName,
    String previousDescription) {

  public static RoleUpdateDetailsDto of(
      String roleCode, String roleName, String description,
      String previousRoleName, String previousDescription) {
    return new RoleUpdateDetailsDto(
        PrivilegeDetailsPermissionType.ROLE_DEFINITION,
        List.of(new PrivilegeDetailsRoleDto(roleCode, null, null, roleName)),
        roleCode, roleName, description, previousRoleName, previousDescription);
  }
}
