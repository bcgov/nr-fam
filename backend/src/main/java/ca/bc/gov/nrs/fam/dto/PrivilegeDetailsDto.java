package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsPermissionType;
import java.util.List;

/**
 * The privileges changed, stored as JSON on the audit row.
 *
 * <p>{@code roles} is populated for End User and Delegated Admin changes and
 * absent for Application Admin changes, which have no role granularity. Upstream
 * enforced that with a Pydantic validator; see
 * {@link #isConsistent()}.
 */
import io.swagger.v3.oas.annotations.media.Schema;

public record PrivilegeDetailsDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PrivilegeDetailsPermissionType permissionType,
    List<PrivilegeDetailsRoleDto> roles) {

  /**
   * Whether roles are present or absent as the permission type requires.
   *
   * <p>Port of {@code check_roles_based_on_permission_type}.
   */
  public boolean isConsistent() {
    if (permissionType == PrivilegeDetailsPermissionType.APPLICATION_ADMIN) {
      return roles == null;
    }
    return roles != null;
  }
}
