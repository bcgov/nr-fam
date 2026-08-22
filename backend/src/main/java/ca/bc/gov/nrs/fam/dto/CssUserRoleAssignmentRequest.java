package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ca.bc.gov.nrs.fam.constants.UserType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * A grant of one role, optionally scoped, to one user.
 *
 * <p>When {@code scopeValues} is non-empty, one role is created per value and
 * those are assigned instead of {@code roleName} - see
 * {@link CssRoleNaming#buildScopedRoleName}.
 */
public record CssUserRoleAssignmentRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String userGuid,

    /**
     * IDIR or Business BCeID. Typed rather than a bare string so the constraint
     * reaches the generated client - BC Services Card has no CSS provider, and a
     * grant naming one would fail at assignment time instead of at the boundary.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull UserType userType,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String roleName,

    /**
     * Where to send the "you have been granted access" notification.
     *
     * <p>Supplied by the caller because the user picker already holds it: the
     * directory's search returns an address alongside the GUID, so resolving it
     * again server-side would be a round trip to re-fetch something the browser
     * had and discarded.
     *
     * <p>Client-supplied and used for nothing else. It never decides who is
     * granted what - that comes from {@code userGuid} - so the worst a wrong
     * value does is misdirect a notification saying access was granted. Blank
     * means no notification is sent.
     */
    @Email(message = "target_user_email must be a valid email address")
    String targetUserEmail,

    /**
     * One entry per scope dimension the role requires. Empty means an unscoped
     * grant.
     *
     * <p>A role scoped by district <em>and</em> forest client carries both, and
     * the grant covers every district/client pair - being a submitter for DCC
     * and for client 00001012 is not the same as being one for either.
     */
    @Valid List<CssScopeSelection> scopes) {

  /** Never null, so callers can iterate without a guard. */
  public List<CssScopeSelection> scopes() {
    return scopes == null ? List.of() : scopes;
  }
}
