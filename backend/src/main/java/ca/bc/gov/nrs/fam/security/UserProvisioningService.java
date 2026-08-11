package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.repository.FamUserRepository;
import ca.bc.gov.nrs.fam.security.TokenClaimsReader.TokenIdentity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates or refreshes the {@code fam_user} row for whoever just signed in.
 *
 * <p>Port of {@code auth_function.populate_user_if_necessary}, the other half of
 * the Cognito pre-token-generation Lambda. Cognito ran this on every login; FAM
 * now runs it when the frontend calls the login-bootstrap endpoint.
 *
 * <p>Without this, a first-time user has a valid token but no FAM identity, and
 * every request would fail with {@code requester_not_exists}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProvisioningService {

  private final FamUserRepository userRepository;

  /**
   * Upsert the caller's FAM identity from their token.
   *
   * <p>Mirrors the Lambda's two-step SQL:
   *
   * <ol>
   *   <li>A row matching on identity type + user name but holding no GUID predates
   *       V50 and is adopted by back-filling its GUID, rather than being shadowed
   *       by a duplicate.
   *   <li>Otherwise upsert on the natural key (identity type + GUID), refreshing
   *       user name, subject, business GUID and email - all of which change at the
   *       identity provider over time.
   * </ol>
   *
   * <p>{@code create_user}/{@code update_user} are stamped with FAM's system
   * account, not the user themselves: this row is written by the login flow, not
   * by an administrator's action.
   */
  @Transactional
  public FamUser provisionUser(TokenIdentity identity) {
    String userTypeCode = identity.userTypeCode();

    Optional<FamUser> byGuid = userRepository.findByUserTypeCodeAndUserGuidIgnoreCase(
        userTypeCode, identity.userGuid());

    FamUser user = byGuid.orElseGet(() -> adoptLegacyRowOrCreate(identity, userTypeCode));

    // Refresh the mutable details on every sign-in, as the Lambda's ON CONFLICT
    // DO UPDATE did.
    user.setUserName(identity.userName());
    user.setOidcUserId(identity.oidcUserId());
    user.setEmail(identity.email());
    if (identity.businessGuid() != null) {
      user.setBusinessGuid(identity.businessGuid());
    }
    user.setUpdateUser(FamConstants.SYSTEM_ACCOUNT_NAME);

    FamUser saved = userRepository.save(user);
    log.debug("Provisioned FAM user {} ({})", saved.getUserId(), saved.getUserName());
    return saved;
  }

  private FamUser adoptLegacyRowOrCreate(TokenIdentity identity, String userTypeCode) {
    Optional<FamUser> byName = userRepository.findByUserTypeCodeAndUserNameIgnoreCase(
        userTypeCode, identity.userName());

    if (byName.isPresent() && byName.get().getUserGuid() == null) {
      FamUser legacy = byName.get();
      log.debug("Adopting pre-V50 user row {} by back-filling its user_guid",
          legacy.getUserId());
      legacy.setUserGuid(identity.userGuid());
      return legacy;
    }

    // A name match with a *different* GUID is a different person - identity
    // providers reuse names - so a new row is correct.
    FamUser created = new FamUser();
    created.setUserTypeCode(userTypeCode);
    created.setUserGuid(identity.userGuid());
    created.setCreateUser(FamConstants.SYSTEM_ACCOUNT_NAME);
    log.debug("Creating new FAM user for {}", identity.userName());
    return created;
  }
}
