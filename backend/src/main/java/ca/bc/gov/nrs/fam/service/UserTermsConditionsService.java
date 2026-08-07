package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.entity.FamUserTermsConditions;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.repository.FamUserRepository;
import ca.bc.gov.nrs.fam.repository.FamUserTermsConditionsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Port of {@code crud_user_terms_conditions.py}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserTermsConditionsService {

  private final FamUserTermsConditionsRepository termsConditionsRepository;
  private final FamUserRepository userRepository;

  /**
   * Record acceptance of the current terms for a user.
   *
   * @throws FamHttpException 409 if this user already accepted this version.
   *     Upstream returned a conflict rather than making the call idempotent, so
   *     the frontend can tell a double submission from a first acceptance.
   */
  @Transactional
  public FamUserTermsConditions acceptCurrentTerms(Long userId, String requesterName) {
    String version = FamConstants.CURRENT_TERMS_AND_CONDITIONS_VERSION;
    log.debug("Recording terms and conditions acceptance for user {} version {}", userId, version);

    if (termsConditionsRepository.existsByUserUserIdAndVersion(userId, version)) {
      throw FamHttpException.conflict(
          ErrorCode.INVALID_OPERATION, "User already accepted terms and conditions.");
    }

    FamUser user = userRepository.findById(userId)
        .orElseThrow(() -> FamHttpException.badRequest(
            ErrorCode.REQUESTER_NOT_EXISTS, "User " + userId + " not found."));

    FamUserTermsConditions acceptance = new FamUserTermsConditions();
    acceptance.setUser(user);
    acceptance.setVersion(version);
    acceptance.setCreateUser(requesterName);

    return termsConditionsRepository.save(acceptance);
  }
}
