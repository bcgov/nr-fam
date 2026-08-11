package ca.bc.gov.nrs.fam.dto;

/** A target user that could not be verified, with the reason. */
public record FailedTargetUser(String userName, String userGuid, String errorReason) {}
