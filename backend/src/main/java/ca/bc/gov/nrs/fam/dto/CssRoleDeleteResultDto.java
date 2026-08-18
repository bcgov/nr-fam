package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * What removing a role actually removed.
 *
 * <p>Deleting a role is not one deletion. A scoped role has a CSS role per scope
 * value granted, and every role has a sidecar holding its description, so the
 * screen asked for one thing and several disappeared. Reporting them lets the
 * confirmation be honest about what happened rather than saying "deleted".
 *
 * <p>Scope markers are never included: {@code HAS_DISTRICT_ROLE} is shared by
 * every scoped role on the integration, and removing it would silently unscope
 * the others.
 */
public record CssRoleDeleteResultDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleName,

    /** Every CSS role removed: the role, its sidecar and any derived from it. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> removedRoles,

    /**
     * Delegations withdrawn with it, from FAM's own integration.
     *
     * <p>A delegation names the role it authorises, so one outliving its role is
     * not harmless: a grant creates a role it cannot find, so an orphaned
     * delegation would let a delegated administrator bring the role back.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> removedDelegations,

    /** People who held the role, and have now lost it. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int membersAffected) {}
