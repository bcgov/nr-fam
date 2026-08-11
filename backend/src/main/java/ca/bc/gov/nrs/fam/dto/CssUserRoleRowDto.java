package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One user's assignment of one role, for the permissions table.
 *
 * <p>Assembled by fanning out over every role in the integration: CSS has no
 * "all users in this integration" endpoint, only the users of a given role.
 *
 * <p>Several columns FAM shows have no CSS source - there is no granted-on date,
 * no expiry and no organisation - so they are absent here rather than returned
 * as empty values that would render as blank cells.
 */
public record CssUserRoleRowDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String username,

    /** {@code IDIR} or {@code BCEID}, derived from the username suffix. */
    String domain,

    String firstName,
    String lastName,
    String email,

    /** The base role, with any scope suffix stripped back off. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleName,

    /** Recovered from the role name, the only place it is recorded. */
    String scopeType,
    String scopeValue) {}
