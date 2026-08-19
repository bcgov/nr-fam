package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How many people hold one role.
 *
 * <p><b>Counts people, not assignments.</b> A scoped role is granted as one CSS
 * role per scope value - {@code FREP_EDITOR_DISTRICT-DCC},
 * {@code FREP_EDITOR_DISTRICT-DKA} - and nobody holds the base role itself. The
 * count therefore spans a role and everything derived from it, and someone
 * granted two districts counts once.
 *
 * <p>Not part of {@link CssRoleOptionDto}, because arriving at these numbers
 * costs one upstream request per role. The role picker on the grant screen needs
 * the roles but not the counts, and should not pay for them.
 */
public record CssRoleMemberCountDto(
    /** The role as {@link CssRoleOptionDto#name} reports it. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleName,

    /** Distinct users holding the role or anything derived from it. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int memberCount) {}
