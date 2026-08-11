package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.FamUserUpdateEntryDto;
import ca.bc.gov.nrs.fam.dto.FamUserUpdateResponse;
import ca.bc.gov.nrs.fam.dto.UserLookupBceidUserDto;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import ca.bc.gov.nrs.fam.repository.FamUserRepository;
import ca.bc.gov.nrs.fam.security.Requester;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refreshes stored user details from the identity directory, in bulk.
 *
 * <p>Port of {@code crud_user.update_user_info_from_idim_source}. Names and email
 * addresses change at the identity provider without FAM being told, so this is
 * run periodically to reconcile.
 *
 * <p>Every user is processed independently: a failure is recorded against that
 * user and the run continues. A single unreachable record must not abort a sweep
 * of the whole table.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserInfoRefreshService {

  /** IDIM holds no data for BC Services Card users, so they are skipped. */
  private static final List<String> REFRESHABLE_USER_TYPES =
      List.of(UserType.IDIR.getCode(), UserType.BCEID.getCode());

  private final FamUserRepository userRepository;
  private final UserLookupClient userLookupClient;
  private final FamProperties famProperties;

  /**
   * @param usePagination process one page at a time. The table is large enough
   *     that a full sweep can exceed a sensible request timeout, so the caller
   *     can drive it in chunks.
   */
  @Transactional
  public FamUserUpdateResponse refreshFromDirectory(boolean usePagination, int page, int perPage) {
    OffsetDateTime runOn = OffsetDateTime.now();


    long totalUsers = userRepository.count();

    List<FamUser> users = usePagination
        ? userRepository.findByUserTypeCodeIn(
            REFRESHABLE_USER_TYPES, PageRequest.of(Math.max(0, page - 1), perPage)).getContent()
        : userRepository.findAllByUserTypeCodeIn(REFRESHABLE_USER_TYPES);

    // Users excluded by the query above are reported as ignored, not silently
    // dropped, so the report accounts for every row.
    List<FamUserUpdateEntryDto> ignored = new ArrayList<>();
    if (!usePagination) {
      userRepository.findAll().stream()
          .filter(u -> !REFRESHABLE_USER_TYPES.contains(u.getUserTypeCode()))
          .map(UserInfoRefreshService::toEntry)
          .forEach(ignored::add);
    }

    List<FamUserUpdateEntryDto> success = new ArrayList<>();
    List<FamUserUpdateEntryDto> failed = new ArrayList<>();
    List<FamUserUpdateEntryDto> mismatch = new ArrayList<>();

    for (FamUser user : users) {
      try {
        refreshOne(user, success, failed, mismatch);
      } catch (Exception e) {
        log.debug("Failed to refresh user {}: {}", user.getUserName(), e.getMessage());
        failed.add(toEntry(user));
      }
    }

    String elapsed = "%ss".formatted(
        Duration.between(runOn, OffsetDateTime.now()).toMillis() / 1000.0);

    log.info("User info refresh: {} updated, {} failed, {} ignored, {} mismatched",
        success.size(), failed.size(), ignored.size(), mismatch.size());

    return new FamUserUpdateResponse(
        totalUsers, page, users.size(), runOn, elapsed,
        success, failed, ignored, mismatch);
  }

  private void refreshOne(
      FamUser user,
      List<FamUserUpdateEntryDto> success,
      List<FamUserUpdateEntryDto> failed,
      List<FamUserUpdateEntryDto> mismatch) {

    boolean found;
    String foundGuid;

    if (UserType.IDIR.getCode().equals(user.getUserTypeCode())) {
      // The directory cannot look up an IDIR user by GUID, so this searches by
      // name and cross-checks the GUID below.
      Optional<UserLookupIdirUserDto> result =
          userLookupClient.getIdirDetail(user.getUserName());
      found = result.isPresent();
      foundGuid = result.map(UserLookupIdirUserDto::guid).orElse(null);

      result.ifPresent(idir ->
          applyIfPresent(user, idir.firstName(), idir.lastName(), idir.email(), null));

    } else {
      // BCeID: look up by GUID when we have one, otherwise by name and back-fill
      // the GUID we get back.
      boolean hasGuid = user.getUserGuid() != null && !user.getUserGuid().isBlank();

      Optional<UserLookupBceidUserDto> result = userLookupClient.getBusinessBceid(
          hasGuid ? UserLookupClient.SearchBy.USER_GUID : UserLookupClient.SearchBy.USER_ID,
          hasGuid ? user.getUserGuid() : user.getUserName());

      found = result.isPresent();
      foundGuid = result.map(UserLookupBceidUserDto::guid).orElse(null);

      result.ifPresent(bceid -> {
        if (hasGuid) {
          // Looked up by GUID, so the name is the thing that may have changed.
          user.setUserName(bceid.userId());
        }
        applyIfPresent(user, bceid.firstName(), bceid.lastName(), bceid.email(),
            bceid.businessGuid());
      });
    }

    if (!found) {
      log.debug("The directory could not find {} ({})", user.getUserName(), user.getUserTypeCode());
      failed.add(toEntry(user));
      return;
    }

    // A stored GUID that disagrees with IDIM means this user name now belongs to
    // somebody else. Writing the new person's details onto this FAM identity
    // would silently transfer their access, so the record is left alone.
    if (user.getUserGuid() != null && !user.getUserGuid().equalsIgnoreCase(foundGuid)) {
      log.debug("Skipping {}: stored GUID does not match IDIM", user.getUserName());
      mismatch.add(toEntry(user));
      return;
    }

    user.setUserGuid(foundGuid);
    user.setUpdateUser(famProperties.updateUserInfo().requesterName());
    userRepository.save(user);

    success.add(toEntry(user));
  }

  /** Null values from the identity directory are not written; an absent field must not blank a stored one. */
  private static void applyIfPresent(
      FamUser user, String firstName, String lastName, String email, String businessGuid) {
    if (firstName != null) {
      user.setFirstName(firstName);
    }
    if (lastName != null) {
      user.setLastName(lastName);
    }
    if (email != null) {
      user.setEmail(email);
    }
    if (businessGuid != null) {
      user.setBusinessGuid(businessGuid);
    }
  }

  private static FamUserUpdateEntryDto toEntry(FamUser user) {
    return new FamUserUpdateEntryDto(
        user.getUserId(), user.getUserName(), user.getUserTypeCode(),
        user.getUserGuid(), user.getEmail());
  }
}
