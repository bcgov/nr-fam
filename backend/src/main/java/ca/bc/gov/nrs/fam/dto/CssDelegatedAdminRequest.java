package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.UserType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Appoint or remove a delegated administrator for one role.
 *
 * <p>Deliberately shaped like {@link CssUserRoleAssignmentRequest}: appointing a
 * delegated administrator <em>is</em> granting them a role, just one on FAM's own
 * integration rather than the application's. Keeping the shapes alike means the
 * scope handling is the same on both screens - a delegation is per scope value,
 * so appointing somebody for three districts is three delegations, exactly as
 * granting a scoped role to a user is three assignments.
 *
 * <p>Port of legacy's {@code FamAccessControlPrivilegeCreateRequest}, which took
 * a role id and a list of forest client numbers for the same reason.
 */
public record CssDelegatedAdminRequest(
    /** GUID of the person being appointed. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String userGuid,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull UserType userType,

    /**
     * The role they are being delegated, without any scope suffix.
     *
     * <p>A role of the application being administered, not of FAM.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String roleName,

    /** Required when {@code scopeValues} is non-empty; ignored otherwise. */
    String scopeType,

    /**
     * District codes or forest client numbers.
     *
     * <p>Empty delegates the role itself, which is what an unscoped role needs.
     * For a scoped role it must not be empty: delegating the bare base role would
     * authorise nothing, because a scoped grant only ever assigns per-scope roles.
     */
    List<String> scopeValues) {

  /** Never null, so callers can iterate without a guard. */
  public List<String> scopeValues() {
    return scopeValues == null ? List.of() : scopeValues;
  }
}
