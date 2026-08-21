package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Snapshot of the person a privilege change was made to, stored as JSON on
 * {@code fam_privilege_change_audit.change_target_user_details}.
 *
 * <p>The same shape as {@link PrivilegeChangePerformerDto}, kept as its own type
 * because the two are recorded for different reasons and a column named for the
 * performer should not be typed by the target.
 *
 * <p><b>A snapshot, not a reference.</b> FAM holds no row for the target - a
 * grant routinely names somebody who has never signed in, and FAM keeps no user
 * table at all. Recording the name here is what keeps a target GUID readable
 * later, and it stays truthful if the person is renamed afterwards.
 *
 * <p>Every field except the GUID may be null: the directory is consulted on a
 * best-effort basis and must never fail the change it is describing.
 */
public record PrivilegeChangeTargetDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String userGuid,
    String username,
    String firstName,
    String lastName,
    String email) {}
