package ca.bc.gov.nrs.fam.dto;

/**
 * A forest client reduced to number and name, as the admin surface reports it.
 *
 * <p>Distinct from {@link FamForestClientDto}, which also carries an
 * Active/Inactive status. The admin screens only ever show which clients a
 * delegated admin may act for, so status is not fetched.
 */
import io.swagger.v3.oas.annotations.media.Schema;

public record FamForestClientBase(
    String clientName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String forestClientNumber) {}
