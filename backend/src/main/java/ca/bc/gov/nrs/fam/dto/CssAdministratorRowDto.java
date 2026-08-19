package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One administrator of an application.
 *
 * <p>Separate from {@link CssUserRoleRowDto} because these people are not in the
 * application's own role list at all. An administrator holds
 * {@code APP_ADMIN_22264_DEV} or {@code DELEGATED_ADMIN_22264_DEV} on <b>FAM's
 * own CSS integration</b>, never on the integration being administered - a token
 * carries only the roles of the client it was issued to, so a role sitting on
 * another application's integration would never reach FAM.
 *
 * <p>That is why the Users tab never showed them, and why these tabs need a read
 * of a different integration rather than a filter over the same list.
 *
 * <p>Read-only. Appointing and removing administrators is the grant path's job,
 * which is guarded per tier.
 */
public record CssAdministratorRowDto(
    /** The federated CSS username, {@code <guid>@<idp>}. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String username,

    /** Upper case, recovered from the username. Null if it is not in that form. */
    String userGuid,

    /** IDIR or BCEID, recovered from the username. */
    String domain,

    String firstName,
    String lastName,
    String email,

    /** The tier this row is in, which is also the tab it appears under. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AdminRoleAuthGroup tier,

    /** The CSS role held, e.g. {@code APP_ADMIN_22264_DEV}. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleName,

    /**
     * For a delegated administrator, the application role they may grant.
     *
     * <p>Scope suffix included, so a delegation covering one district reads as
     * {@code FREP_EDITOR_DISTRICT-DCC}. Null for an application administrator,
     * who is delegated no single role - they administer everything.
     */
    String delegatedRoleName) {}
