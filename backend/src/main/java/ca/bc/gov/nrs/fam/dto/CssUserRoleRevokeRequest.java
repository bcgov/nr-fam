package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.UserType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One assignment to take away: this role, from this user.
 *
 * <p>Names the same pieces the listing shows rather than an assignment id,
 * because CSS has no such id - an assignment is the fact that a user holds a
 * role, and nothing more.
 */
public record CssUserRoleRevokeRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String userGuid,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull UserType userType,

    /** The base role, as the listing shows it. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String roleName,

    /**
     * Every scope of the assignment being removed, one value each.
     *
     * <p>These reconstruct the concrete role CSS holds -
     * {@code CHR_FREP_EDITOR_DISTRICT-DCC} - which the listing split apart to
     * display. Revoking the base role instead would take away nothing, because
     * that is not what the user was assigned.
     *
     * <p>All of a compound role's scopes are required: named with only one, the
     * rebuilt name is a role nobody holds and the removal quietly does nothing.
     */
    @Valid List<CssScopeSelection> scopes) {

  public List<CssScopeSelection> scopes() {
    return scopes == null ? List.of() : scopes;
  }
}
