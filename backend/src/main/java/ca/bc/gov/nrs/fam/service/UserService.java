package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.FamUserInfoDto;
import ca.bc.gov.nrs.fam.dto.TargetUser;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.repository.FamUserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read access to FAM users. Port of the read paths in {@code crud_user.py}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

  private final FamUserRepository userRepository;

  @Transactional(readOnly = true)
  public FamUser getUser(Long userId) {
    return userRepository.findById(userId).orElse(null);
  }

  /**
   * User details for an admin screen.
   *
   * @throws FamHttpException 404 when the user does not exist, matching upstream's
   *     "User not found".
   */
  @Transactional(readOnly = true)
  public FamUserInfoDto getUserInfo(Long userId) {
    return userRepository.findById(userId)
        .map(user -> new FamUserInfoDto(user.getUserName(), null,
            user.getFirstName(), user.getLastName(), user.getEmail()))
        .orElseThrow(() -> FamHttpException.notFound(
            ErrorCode.INVALID_REQUEST_PARAMETER, "User not found"));
  }

  /**
   * Find the {@code fam_user} row for an identity, creating it if FAM has not seen
   * them before.
   *
   * <p>Port of {@code crud_user.find_or_create}. The lookup order matters and
   * handles two pieces of history:
   *
   * <ol>
   *   <li>By identity type + GUID, the natural key since V50.
   *   <li>Failing that, by identity type + user name. A row found this way with no
   *       GUID predates V50 and is back-filled rather than duplicated.
   *   <li>Otherwise a new row. A name match whose GUID differs is a
   *       <em>different person</em> - names get reassigned by the identity
   *       provider - so a new row is correct.
   * </ol>
   *
   * <p>An existing user's name is refreshed on the way through, since names change.
   */
  @Transactional
  public FamUser findOrCreate(
      String userTypeCode, String userName, String userGuid, String requesterOidcId) {

    Optional<FamUser> byGuid =
        userRepository.findByUserTypeCodeAndUserGuidIgnoreCase(userTypeCode, userGuid);

    if (byGuid.isPresent()) {
      FamUser user = byGuid.get();
      updateUserNameIfChanged(user, userName, requesterOidcId);
      return user;
    }

    Optional<FamUser> byName =
        userRepository.findByUserTypeCodeAndUserNameIgnoreCase(userTypeCode, userName);

    if (byName.isPresent() && byName.get().getUserGuid() == null) {
      // Pre-V50 row: adopt the GUID rather than creating a duplicate.
      FamUser user = byName.get();
      user.setUserGuid(userGuid);
      user.setUpdateUser(requesterOidcId);
      log.debug("User {} found by name; back-filling their user_guid", user.getUserId());
      return userRepository.save(user);
    }

    FamUser created = new FamUser();
    created.setUserTypeCode(userTypeCode);
    created.setUserName(userName);
    created.setUserGuid(userGuid);
    created.setCreateUser(requesterOidcId);
    FamUser saved = userRepository.save(created);
    log.debug("User created: {}", saved.getUserId());
    return saved;
  }

  /** Names change at the identity provider; keep FAM's copy current. */
  private void updateUserNameIfChanged(FamUser user, String userName, String requesterOidcId) {
    if (userName != null && !userName.equalsIgnoreCase(user.getUserName())) {
      user.setUserName(userName);
      user.setUpdateUser(requesterOidcId);
      userRepository.save(user);
    }
  }

  /**
   * Refresh a user's details from what IDIM returned during verification.
   *
   * <p>Port of {@code update_user_properties_from_verified_target_user}. The user
   * name is deliberately excluded - it is maintained by
   * {@link #findOrCreate}.
   *
   * <p>{@code business_guid} is only written for BCeID users, and only when IDIM
   * supplied one, so an absent value never blanks a stored organisation.
   */
  @Transactional
  public FamUser updateFromVerifiedTargetUser(
      Long userId, TargetUser targetUser, String requesterOidcId) {

    FamUser user = userRepository.findById(userId)
        .orElseThrow(() -> FamHttpException.badRequest(
            ErrorCode.INVALID_REQUEST_PARAMETER, "User " + userId + " not found."));

    user.setFirstName(targetUser.firstName());
    user.setLastName(targetUser.lastName());
    user.setEmail(targetUser.email());

    boolean isBceid = UserType.BCEID.getCode().equals(targetUser.userTypeCode());
    if (isBceid && targetUser.businessGuid() != null) {
      user.setBusinessGuid(targetUser.businessGuid());
    }

    user.setUpdateUser(requesterOidcId);
    return userRepository.save(user);
  }
}
