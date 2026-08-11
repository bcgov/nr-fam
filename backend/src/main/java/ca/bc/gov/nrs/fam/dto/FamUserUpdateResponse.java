package ca.bc.gov.nrs.fam.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Report from the bulk refresh of user details against IDIM.
 *
 * <p>Four outcome lists rather than a success/failure count, because the reasons
 * are operationally different:
 *
 * <ul>
 *   <li>{@code success} - the record was refreshed;
 *   <li>{@code failed} - IDIM could not find the user, or the lookup errored;
 *   <li>{@code ignored} - a BC Services Card user; IDIM holds no data for them;
 *   <li>{@code mismatch} - IDIM returned a different GUID for the user name,
 *       which means the name was reassigned to somebody else. These are left
 *       untouched deliberately: overwriting would attach one person's details to
 *       another person's FAM identity.
 * </ul>
 */
public record FamUserUpdateResponse(
    long totalDbUsersCount,
    int currentPage,
    int usersCountOnPage,
    OffsetDateTime runOn,
    String elapsed,
    List<FamUserUpdateEntryDto> successUserUpdateList,
    List<FamUserUpdateEntryDto> failedUserUpdateList,
    List<FamUserUpdateEntryDto> ignoredUserUpdateList,
    List<FamUserUpdateEntryDto> mismatchUserUpdateList) {}
