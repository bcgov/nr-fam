package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Resolves the authenticated caller into a {@link Requester}.
 *
 * <p>Port of {@code router_guards.get_current_requester} and its helper
 * {@code _parse_custom_requester_fields}.
 *
 * <p><strong>Everything comes from the token.</strong> Identity is read from the
 * claims and roles from {@code client_roles} - see {@link AccessRoleResolver}.
 * Neither is a database read, so no query runs on the authentication path.
 *
 * <p>This used to load a {@code fam_user} row and reject a token that had none,
 * which made "has signed in to FAM at least once" a precondition for every API
 * call. That row contributed nothing to authorisation - the roles that decide
 * what a caller may do have always come from the token - and every field it
 * supplied is on the token too. The table, and the gate with it, are gone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequesterService {

  private final TokenClaimsReader claimsReader;
  private final AccessRoleResolver accessRoleResolver;

  /**
   * Build the {@link Requester} for the current request.
   *
   * @throws FamHttpException 401 when unauthenticated
   */
  public Requester currentRequester() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
      throw new FamHttpException(HttpStatus.UNAUTHORIZED, ErrorCode.PERMISSION_REQUIRED,
          "Request is not authenticated.");
    }
    return fromToken(jwt);
  }

  public Requester fromToken(Jwt jwt) {
    return toRequester(claimsReader.identity(jwt), accessRoleResolver.resolveAccessRoles(jwt));
  }

  /**
   * Derive the two computed flags upstream attached to the requester.
   *
   * <p>{@code isDelegatedAdmin} and {@code requiresAcceptTc} are always false
   * since V94: delegated administration and terms acceptance are CSS concerns,
   * and the tables that backed them are gone.
   */
  public Requester toRequester(
      TokenClaimsReader.TokenIdentity identity, List<String> accessRoles) {

    // Delegated administration and terms-and-conditions acceptance went to CSS
    // in V94 along with the tables that recorded them. Both flags are retained on
    // Requester so the response shape is unchanged, and both are now always false.
    boolean delegatedAdmin = false;
    boolean requiresAcceptTc = false;

    return Requester.builder()
        .oidcUserId(identity.oidcUserId())
        .userName(identity.userName())
        .firstName(identity.firstName())
        .lastName(identity.lastName())
        .email(identity.email())
        .userType(identity.identityProvider().getUserType())
        .userGuid(identity.userGuid())
        .businessGuid(identity.businessGuid())
        .accessRoles(accessRoles)
        .isDelegatedAdmin(delegatedAdmin)
        .requiresAcceptTc(requiresAcceptTc)
        .build();
  }
}
