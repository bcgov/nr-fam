package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

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
 * <p>Appointing is the grant path's job. Removing is done from the row, which is
 * why the delegated role arrives split into a base name and its scopes: those
 * are exactly the fields {@link CssDelegatedAdminRequest} needs to name the one
 * delegation being withdrawn.
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
     * <p><b>Without its scope suffix</b> - {@code FREP_EDITOR}, not
     * {@code FREP_EDITOR_DISTRICT-DCC}. The scopes are parsed out into
     * {@link #scopes()} so the row can be withdrawn without the client having to
     * take the name apart, and so the column can show them as chips the way the
     * users table does.
     *
     * <p>Null for an application administrator, who is delegated no single role -
     * they administer everything the application defines.
     */
    String delegatedRoleName,

    /**
     * What that role is called, as opposed to what it is coded.
     *
     * <p>{@code Submitter (CHR)} rather than {@code CHR_FREP_EDITOR}. Held in a
     * sidecar role beside the role it names - on the <b>application's</b>
     * integration, not on FAM's own, where the delegation itself lives.
     *
     * <p>Null when the role has no label sidecar, which is every role added
     * directly in the CSS console. The client falls back to the code rather than
     * showing an empty pill: a technical name beats none.
     */
    String delegatedRoleDisplayName,

    /**
     * The scopes that delegation covers, empty when it covers the role outright.
     *
     * <p>One row is one scope <em>combination</em>, not a role's whole scope: a
     * person delegated FREP_EDITOR for two districts holds two delegation roles
     * and appears as two rows. Sending all of a row's scopes back on a withdrawal
     * therefore names one role, which is the one the row stands for.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ScopeDto> scopes) {

  public List<ScopeDto> scopes() {
    return scopes == null ? List.of() : scopes;
  }
}
