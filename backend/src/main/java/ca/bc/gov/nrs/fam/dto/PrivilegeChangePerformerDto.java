package ca.bc.gov.nrs.fam.dto;

/**
 * Snapshot of who made a privilege change, stored as JSON on the audit row.
 *
 * <p>Recorded at the time of the change so that later edits to - or removal of -
 * the performing user cannot rewrite history.
 *
 * <p>For system-initiated changes only {@code username} is populated, and it is
 * {@link ca.bc.gov.nrs.fam.constants.FamConstants#SYSTEM_ACCOUNT_NAME}.
 */
import io.swagger.v3.oas.annotations.media.Schema;

public record PrivilegeChangePerformerDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String username,
    String firstName,
    String lastName,
    String email) {}
