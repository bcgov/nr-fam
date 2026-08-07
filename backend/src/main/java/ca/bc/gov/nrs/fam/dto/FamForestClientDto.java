package ca.bc.gov.nrs.fam.dto;

/**
 * A forest client.
 *
 * <p>{@code clientName} and {@code status} are not stored by FAM - they are
 * filled in from the Forest Client API when a response is enriched, and are null
 * otherwise.
 */
import io.swagger.v3.oas.annotations.media.Schema;

public record FamForestClientDto(
    String clientName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String forestClientNumber,
    FamForestClientStatusDto status) {}
