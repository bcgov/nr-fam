package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One administrative permission the signed-in user holds.
 *
 * <p>What "My permissions" shows. It is the caller's <b>administrative</b>
 * access - what they may administer in FAM - not the application roles they hold
 * as an ordinary user of those applications. FAM never sees the latter: an
 * application's own roles live on its own CSS integration and reach that
 * application's tokens, never FAM's.
 *
 * <p>A row is derived from one role name on the caller's token. The names carry
 * the application inside them - {@code APP_ADMIN_22264_DEV} - so the id is
 * resolved back to a project name here rather than shown raw.
 */
public record SelfPermissionDto(
    /** Null for FAM_ADMIN, which administers every application rather than one. */
    Integer cssIntegrationId,

    /** Null for FAM_ADMIN, for the same reason. */
    String environment,

    /** The project name from CSS, or a stand-in when it cannot be resolved. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String applicationName,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AdminRoleAuthGroup role,

    /** How the tier reads on screen, e.g. "Application administrator". */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleDescription,

    /** The underlying CSS role name, so the screen can show what it derives from. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleName,

    /**
     * For a delegated administrator, the role they may hand out.
     *
     * <p>Null at every other tier: an application administrator may grant
     * whatever the application defines, so there is no one role to name.
     *
     * <p>Without it a delegated administrator's rows are indistinguishable.
     * Somebody delegated two roles in one application holds two roles and gets
     * two rows, and both said "Sandbox REPT / TEST / Delegated administrator" -
     * the same sentence twice, with nothing on screen explaining why it appeared
     * at all, let alone twice.
     *
     * <p>The base role, without the scope encoded into the CSS name. The scopes
     * travel separately below, so a delegation covering two districts reads as
     * one role with two scopes rather than as a role with a strange name.
     */
    String delegatedRoleName,

    /** What that role is called, from its label sidecar. Null when it has none. */
    String delegatedRoleDisplayName,

    /**
     * What the delegation is narrowed to, empty when it is not narrowed.
     *
     * <p>Carried for the same reason as the role: two delegations of one role
     * for different districts are two rows that would otherwise read alike.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    java.util.List<ScopeDto> scopes) {}
