package ca.bc.gov.nrs.fam.dto;

import java.util.UUID;
/**
 * One user in a bulk-refresh report.
 *
 * <p>Upstream returned untyped dictionaries here; this pins the shape so the
 * generated client and the operator reading the response both know what to
 * expect. The fields are the ones upstream logged.
 */
public record FamUserUpdateEntryDto(
    UUID userId, String userName, String userType, String userGuid, String email) {}
