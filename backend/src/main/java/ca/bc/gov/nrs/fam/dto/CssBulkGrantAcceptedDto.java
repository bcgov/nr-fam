package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A bulk upload has been received and is being applied in the background.
 *
 * <p>Returned instead of the per-row outcome, which a file of a couple of hundred
 * people took longer to produce than the connection would stay open. The rows
 * still land; their outcome is in each user's history and in the users table
 * once applied, and a re-upload marks whatever was granted as already held.
 */
public record CssBulkGrantAcceptedDto(
    /** Non-blank rows in the file, before validation. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int rowCount) {}
