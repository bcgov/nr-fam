package ca.bc.gov.nrs.fam.dto;

import java.util.List;

/**
 * Outcome of verifying a batch of target users against IDIM.
 *
 * <p>Split rather than all-or-nothing: verified users are granted, failed users
 * are reported individually.
 */
public record TargetUserValidationResult(
    List<TargetUser> verifiedUsers, List<FailedTargetUser> failedUsers) {}
