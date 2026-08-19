package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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

    /** DISTRICT or FOREST_CLIENT, or null when the role is not scoped. */
    String scopeType,

    /** The district or forest client the role applies to. Null when unscoped. */
    String scopeValue) {}
