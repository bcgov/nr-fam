package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.IdimSearchUserParamType;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.FailedTargetUser;
import ca.bc.gov.nrs.fam.dto.IdimProxyBceidInfoDto;
import ca.bc.gov.nrs.fam.dto.IdimProxyIdirInfoDto;
import ca.bc.gov.nrs.fam.dto.TargetUser;
import ca.bc.gov.nrs.fam.dto.TargetUserValidationResult;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.IdimProxyService;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Verifies target users against IDIM before their access is changed.
 *
 * <p>Port of {@code crud/validator/target_user_validator.py}. FAM will not grant
 * access to an identity it cannot confirm exists, and it will not trust the
 * name/GUID pairing the caller supplied - IDIM is the authority for both.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TargetUserValidationService {

  private final IdimProxyService idimProxyService;
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;

  /**
   * Verify a batch, collecting failures rather than aborting.
   *
   * <p>One unverifiable user must not block the rest of a batch grant, so each is
   * validated independently and failures are returned alongside successes.
   */
  public TargetUserValidationResult validateTargetUsers(
      Requester requester, List<TargetUser> targetUsers, FamRole role) {

    ApiInstanceEnv apiInstanceEnv = apiInstanceEnvResolver.resolve(role.getApplication());

    List<TargetUser> verified = new ArrayList<>();
    List<FailedTargetUser> failed = new ArrayList<>();

    for (TargetUser targetUser : targetUsers) {
      try {
        verified.add(verifyUserExists(requester, targetUser, apiInstanceEnv));
      } catch (Exception e) {
        log.error("Validation failed for user {}: {}", targetUser.userName(), e.getMessage());
        failed.add(new FailedTargetUser(
            targetUser.userName(), targetUser.userGuid(), e.getMessage()));
      }
    }

    return new TargetUserValidationResult(verified, failed);
  }

  /**
   * Confirm one user exists and enrich them from IDIM.
   *
   * @return the target user with name, email and business GUID taken from IDIM
   * @throws FamHttpException if the user does not exist, or the supplied
   *     identifiers contradict what IDIM returns
   */
  public TargetUser verifyUserExists(
      Requester requester, TargetUser targetUser, ApiInstanceEnv apiInstanceEnv) {

    UserType userType = UserType.fromCode(targetUser.userTypeCode())
        .orElseThrow(() -> FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
            "Invalid user type: " + targetUser.userTypeCode() + "."));

    return switch (userType) {
      case IDIR -> verifyIdir(requester, targetUser, apiInstanceEnv);
      case BCEID -> verifyBceid(requester, targetUser, apiInstanceEnv);
      default -> throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "Invalid user type: " + targetUser.userTypeCode() + ".");
    };
  }

  private TargetUser verifyIdir(
      Requester requester, TargetUser targetUser, ApiInstanceEnv apiInstanceEnv) {

    // IDIM cannot look up an IDIR user by GUID, so the search is by user name and
    // the GUID is cross-checked afterwards.
    IdimProxyIdirInfoDto result =
        idimProxyService.lookupIdir(targetUser.userName(), requester, apiInstanceEnv);

    requireFound(result.found(), targetUser);

    // A mismatch here means the caller paired a name with someone else's GUID.
    // The UI cannot produce this, but a direct API call can.
    if (!java.util.Objects.equals(result.guid(), targetUser.userGuid())) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "Invalid request, found user %s with user type %s, but found user guid %s does not "
              .formatted(targetUser.userName(), targetUser.userTypeCode(), result.guid())
              + "match the user guid in request " + targetUser.userGuid());
    }

    return targetUser.toBuilder()
        .firstName(result.firstName())
        .lastName(result.lastName())
        .email(result.email())
        // IDIR users have no business GUID; upstream cleared it here too.
        .businessGuid(null)
        .build();
  }

  private TargetUser verifyBceid(
      Requester requester, TargetUser targetUser, ApiInstanceEnv apiInstanceEnv) {

    // BCeID lookup is by GUID, which is stable, and the name is cross-checked.
    IdimProxyBceidInfoDto result = idimProxyService.lookupBusinessBceid(
        IdimSearchUserParamType.USER_GUID, targetUser.userGuid(), requester, apiInstanceEnv);

    requireFound(result.found(), targetUser);

    // Case-insensitive: BCeID user ids are not case-stable.
    String foundUserName = result.userId() == null
        ? null
        : result.userId().toLowerCase(Locale.ROOT);
    String requestedUserName = targetUser.userName() == null
        ? null
        : targetUser.userName().toLowerCase(Locale.ROOT);

    if (!java.util.Objects.equals(foundUserName, requestedUserName)) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "Invalid request, found user %s with user type %s, but found username %s does not "
              .formatted(targetUser.userGuid(), targetUser.userTypeCode(), foundUserName)
              + "match the username in request " + requestedUserName);
    }

    return targetUser.toBuilder()
        .businessGuid(result.businessGuid())
        .firstName(result.firstName())
        .lastName(result.lastName())
        .email(result.email())
        .build();
  }

  private static void requireFound(boolean found, TargetUser targetUser) {
    if (!found) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "Invalid request, cannot find user %s %s with user type %s".formatted(
              targetUser.userName(), targetUser.userGuid(), targetUser.userTypeCode()));
    }
  }

  /**
   * A BCeID requester may only manage users in their own organisation.
   *
   * <p>Port of {@code validate_bceid_same_org}. A missing business GUID on either
   * side is a failure, not a pass - the rule fails closed.
   *
   * @throws IllegalArgumentException when the rule is violated; callers decide
   *     whether that aborts the request or fails one user.
   */
  public void validateBceidSameOrg(Requester requester, List<TargetUser> targetUsers) {
    if (requester.userType() != UserType.BCEID) {
      return;
    }

    String requesterOrg = requester.businessGuid();
    for (TargetUser targetUser : targetUsers) {
      String targetOrg = targetUser.businessGuid();
      if (requesterOrg == null || targetOrg == null) {
        throw new IllegalArgumentException("Requester or target user business GUID is missing.");
      }
      if (!requesterOrg.equalsIgnoreCase(targetOrg)) {
        throw new IllegalArgumentException(
            "Managing user " + targetUser.userName()
                + " from a different organization is not allowed.");
      }
    }
  }

  /**
   * Split a batch by the same-organisation rule.
   *
   * <p>Port of {@code validate_bceid_same_org_users}. Only applies when granting
   * BCeID users; an IDIR grant passes everything through.
   *
   * @return verified users first, then the rejected ones paired with a reason
   */
  public SameOrgSplit splitBySameOrg(
      Requester requester, List<TargetUser> users, String userTypeCode) {

    if (!UserType.BCEID.getCode().equals(userTypeCode)) {
      return new SameOrgSplit(users, List.of());
    }

    List<TargetUser> valid = new ArrayList<>();
    List<FailedTargetUser> failed = new ArrayList<>();
    for (TargetUser user : users) {
      try {
        validateBceidSameOrg(requester, List.of(user));
        valid.add(user);
      } catch (IllegalArgumentException e) {
        failed.add(new FailedTargetUser(user.userName(), user.userGuid(), e.getMessage()));
      }
    }
    return new SameOrgSplit(valid, failed);
  }

  public record SameOrgSplit(List<TargetUser> validUsers, List<FailedTargetUser> failedUsers) {}
}
