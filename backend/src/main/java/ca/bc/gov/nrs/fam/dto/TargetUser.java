package ca.bc.gov.nrs.fam.dto;

import lombok.Builder;

/**
 * A user being granted or revoked access, as opposed to the requester performing
 * the change.
 *
 * <p>Starts out holding only what the request supplied (name, GUID, type) and is
 * filled in from IDIM during verification.
 */
@Builder(toBuilder = true)
public record TargetUser(
    Long userId,
    String userName,
    String userGuid,
    String userTypeCode,
    String businessGuid,
    String firstName,
    String lastName,
    String email) {}
