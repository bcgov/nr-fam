package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ca.bc.gov.nrs.fam.constants.UserType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
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
    @Valid List<CssScopeSelection> scopes,

    /**
     * The last day this access is good for, or null for access that does not
     * expire.
     *
     * <p>A date rather than an instant, and read in BC time: the person granting
     * is choosing a day, not a moment, and the access lasts to the end of it.
     * That is how the legacy application read the same field, so a grant made
     * there and one made here mean the same thing.
     *
     * <p>Recorded in CSS as a sidecar role assigned alongside the grant - see
     * {@link CssRoleNaming#EXPIRY_PREFIX}. CSS has nowhere else to put it: a role
     * is a name, and the assignment call carries nothing but names.
     */
    @Schema(type = "string", format = "date", example = "2026-09-30")
    LocalDate expiresOn) {

  /** Never null, so callers can iterate without a guard. */
  public List<CssScopeSelection> scopes() {
    return scopes == null ? List.of() : scopes;
  }
}
