package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsPermissionType;
import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsScopeType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * What was recorded when a role was defined, stored as the audit row's
 * {@code privilege_details}.
 *
 * <p><b>Deliberately a superset of {@link PrivilegeDetailsDto}.</b> That column
 * holds one document per row and its shape follows the change type, but the
 * history reader parses every row it returns as a {@code PrivilegeDetailsDto}. A
 * role definition cannot reach that reader today - history is keyed on a target
 * user GUID and this row has none - but an audit document outlives the code that
 * wrote it, so it carries {@code permissionType} and {@code roles} as well.
 * Anything reading it the old way sees a coherent record naming the role rather
 * than failing on an unrecognised shape.
 *
 * <p>{@code roles} therefore repeats the code held in {@code roleCode}. The
 * repetition is the point: one field is what the old shape can see, the other is
 * where the full definition lives.
 */
public record RoleDefinitionDetailsDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    PrivilegeDetailsPermissionType permissionType,

    /** One entry naming the role, so the document reads as a privilege detail. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    List<PrivilegeDetailsRoleDto> roles,

    /** The raw code, which is also the CSS role name. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleCode,

    /** The short name as entered, held in CSS on a FAM:LABEL sidecar. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleName,

    /** The long description, held on a FAM:DESC sidecar. Null when not given. */
    String description,

    /** What must be chosen when the role is granted. Null for an unscoped role. */
    PrivilegeDetailsScopeType requiredScopeType) {

  /** Builds the document, keeping the two representations of the code in step. */
  public static RoleDefinitionDetailsDto of(
      String roleCode, String roleName, String description,
      PrivilegeDetailsScopeType requiredScopeType) {

    return new RoleDefinitionDetailsDto(
        PrivilegeDetailsPermissionType.ROLE_DEFINITION,
        List.of(new PrivilegeDetailsRoleDto(roleCode, null, null)),
        roleCode,
        roleName,
        description,
        requiredScopeType);
  }
}
