package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One application role the signed-in user holds.
 *
 * <p>Distinct from {@link SelfPermissionDto}, which says what they may
 * <em>administer</em>. This says what they can do as an ordinary user of an
 * application - the roles its own tokens carry.
 *
 * <p>The scope is recovered from the role name, because that is the only place
 * it exists: a scoped grant creates one CSS role per scope value
 * ({@code FREP_EDITOR_DISTRICT-DCC}) and CSS roles hold no attributes. So a
 * person granted three districts holds three roles, and appears as three rows -
 * which is what they actually have.
 */
public record SelfApplicationRoleDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int cssIntegrationId,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String environment,

    /** The project name from CSS. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String applicationName,

    /** The concrete CSS role held, scope suffix and all. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleName,

    /** The role without its scope suffix - what the administrator granted. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String baseRoleName,

    /** The short name from the role's FAM:LABEL sidecar. Null when it has none. */
    String roleDisplayName,

    /** The long description from the FAM:DESC sidecar. Null when it has none. */
    String roleDescription,

    /**
     * Every scope this role is held under; empty when it is not scoped.
     *
     * <p>A role scoped by district and forest client carries both, and the
     * screen shows a chip for each.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ScopeDto> scopes) {

  public List<ScopeDto> scopes() {
    return scopes == null ? List.of() : scopes;
  }
}
