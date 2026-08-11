package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.repository.FamUserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the authenticated caller into a {@link Requester}.
 *
 * <p>Port of {@code router_guards.get_current_requester} and its helper
 * {@code _parse_custom_requester_fields}.
 *
 * <p>Identity comes from the token; <strong>roles come from the database</strong>.
 * That is the one behavioural change from Cognito, where the pre-token Lambda
 * baked roles into {@code cognito:groups} at login. See {@link AccessRoleResolver}.
 *
 * <p>A token without a matching {@code fam_user} row is rejected. The row is
 * created by the login-bootstrap endpoint, which the frontend calls immediately
 * after sign-in.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequesterService {

  private final FamUserRepository userRepository;
  private final TokenClaimsReader claimsReader;
  private final AccessRoleResolver accessRoleResolver;

  /**
   * Build the {@link Requester} for the current request.
   *
   * @throws FamHttpException 401 when unauthenticated, or 403
   *     {@code requester_not_exists} when the token's identity has no
   *     {@code fam_user} row
   */
  @Transactional(readOnly = true)
  public Requester currentRequester() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
      throw new FamHttpException(HttpStatus.UNAUTHORIZED, ErrorCode.PERMISSION_REQUIRED,
          "Request is not authenticated.");
    }
    return fromToken(jwt);
  }

  @Transactional(readOnly = true)
  public Requester fromToken(Jwt jwt) {
    TokenClaimsReader.TokenIdentity identity = claimsReader.identity(jwt);

    FamUser famUser = userRepository
        .findByUserTypeCodeAndUserGuidIgnoreCase(identity.userTypeCode(), identity.userGuid())
        .orElseThrow(() -> new FamHttpException(HttpStatus.FORBIDDEN,
            ErrorCode.REQUESTER_NOT_EXISTS, "Requester does not exist, action is not allowed."));

    List<String> accessRoles = accessRoleResolver.resolveAccessRoles(jwt);

    return toRequester(famUser, accessRoles);
  }

  /** Used by the login-bootstrap flow, where the user row has just been provisioned. */
  @Transactional(readOnly = true)
  public Requester forProvisionedUser(FamUser famUser, List<String> accessRoles) {
    return toRequester(famUser, accessRoles);
  }

  /**
   * Derive the two computed flags upstream attached to the requester.
   *
   * <p>{@code isDelegatedAdmin} and {@code requiresAcceptTc} are always false
   * since V94: delegated administration and terms acceptance are CSS concerns,
   * and the tables that backed them are gone.
   */
  private Requester toRequester(FamUser famUser, List<String> accessRoles) {
    UserType userType = UserType.fromCode(famUser.getUserTypeCode()).orElse(null);

    // Delegated administration and terms-and-conditions acceptance went to CSS
    // in V94 along with the tables that recorded them. Both flags are retained on
    // Requester so the response shape is unchanged, and both are now always false.
    boolean delegatedAdmin = false;
    boolean requiresAcceptTc = false;

    return Requester.builder()
        .userId(famUser.getUserId())
        .oidcUserId(famUser.getOidcUserId())
        .userName(famUser.getUserName())
        .firstName(famUser.getFirstName())
        .lastName(famUser.getLastName())
        .email(famUser.getEmail())
        .userType(userType)
        .userGuid(famUser.getUserGuid())
        .businessGuid(famUser.getBusinessGuid())
        .accessRoles(accessRoles)
        .isDelegatedAdmin(delegatedAdmin)
        .requiresAcceptTc(requiresAcceptTc)
        .build();
  }

}
