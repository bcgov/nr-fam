package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.entity.FamUserTermsAcceptance;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.repository.FamUserTermsAcceptanceRepository;
import ca.bc.gov.nrs.fam.security.AuditUser;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The FAM Terms of Use, and who has to accept them.
 *
 * <p>Port of legacy's {@code crud_user_terms_conditions} and the
 * {@code requires_accept_tc} rule in {@code router_guards}. The rule is
 * unchanged: a Business BCeID user who is a delegated administrator anywhere must
 * have accepted the current version. IDIR users are government staff already
 * bound by their employment terms and are never asked, and neither is anybody
 * who is not a delegated administrator.
 *
 * <p>Delegated administrator used to mean "has a row in
 * {@code fam_access_control_privilege}". That table is CSS roles now, so it means
 * holding any {@code DELEGATED_ADMIN_} role on the token.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TermsAcceptanceService {

  private final FamUserTermsAcceptanceRepository repository;

  /** Whether any of these roles makes the holder a delegated administrator. */
  public static boolean holdsDelegatedAdminRole(List<String> accessRoles) {
    return accessRoles != null && accessRoles.stream()
        .anyMatch(role -> FamAdminRole.tierOf(role)
            .filter(tier -> tier == AdminRoleAuthGroup.DELEGATED_ADMIN)
            .isPresent());
  }

  /**
   * Whether this person must accept the current terms before doing anything.
   *
   * <p>Reads the database only for a Business BCeID delegated administrator - the
   * rare case - so an IDIR request still resolves from the token alone.
   */
  @Transactional(readOnly = true)
  public boolean requiresAcceptance(UserType userType, String userGuid, boolean delegatedAdmin) {
    if (userType != UserType.BCEID || !delegatedAdmin) {
      return false;
    }
    return !repository.existsByAcceptedUserAndTermsVersion(
        AuditUser.of(userType, userGuid), FamConstants.CURRENT_TERMS_AND_CONDITIONS_VERSION);
  }

  /**
   * Record that the requester accepts the current terms.
   *
   * <p>Idempotent, unlike legacy's 409 on a repeat: a double click or a retry
   * after a dropped response is somebody accepting, not a conflict, and the
   * outcome they wanted is already true.
   *
   * @throws FamHttpException 403 for anybody the terms do not apply to. Recording
   *     an acceptance nobody asked for would be a row that means nothing.
   */
  // Deliberately not @Transactional. A duplicate insert marks the surrounding
  // transaction rollback-only, and catching it below would then fail at commit
  // instead - saveAndFlush runs in its own.
  public void accept(Requester requester) {
    if (!requester.isExternalDelegatedAdmin()) {
      throw FamHttpException.forbidden(ErrorCode.INVALID_OPERATION,
          "Only Business BCeID delegated administrators accept the Terms of Use.");
    }

    String acceptedUser = AuditUser.of(requester);
    String version = FamConstants.CURRENT_TERMS_AND_CONDITIONS_VERSION;
    if (repository.existsByAcceptedUserAndTermsVersion(acceptedUser, version)) {
      return;
    }

    FamUserTermsAcceptance acceptance = new FamUserTermsAcceptance();
    acceptance.setAcceptedUser(acceptedUser);
    acceptance.setUserName(requester.userName());
    acceptance.setBusinessGuid(requester.businessGuid());
    acceptance.setTermsVersion(version);
    acceptance.setCreateUser(acceptedUser);

    try {
      repository.saveAndFlush(acceptance);
      log.info("{} accepted the Terms of Use, version {}.", requester.userName(), version);
    } catch (DataIntegrityViolationException e) {
      // Two requests raced past the check above. The unique constraint kept one
      // row, which is the outcome both of them wanted.
      log.debug("Terms of Use acceptance for {} was already recorded.", requester.userName());
    }
  }
}
