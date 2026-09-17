package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.service.TermsAcceptanceService;
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
 * Neither is a database read. The one query on this path is whether a Business
 * BCeID delegated administrator has accepted the Terms of Use - see
 * {@link TermsAcceptanceService} - and nobody else triggers it.
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
  private final TermsAcceptanceService termsAcceptanceService;

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
   * <p>{@code isDelegatedAdmin} comes from the roles: any {@code DELEGATED_ADMIN_}
   * role on the token. {@code requiresAcceptTc} is the one exception to
   * "everything comes from the token" - whether somebody accepted the Terms of
   * Use is recorded nowhere else. It is only looked up for a Business BCeID
   * delegated administrator, so every other request still makes no query.
   */
  public Requester toRequester(
      TokenClaimsReader.TokenIdentity identity, List<String> accessRoles) {

    UserType userType = identity.identityProvider().getUserType();
    boolean delegatedAdmin = TermsAcceptanceService.holdsDelegatedAdminRole(accessRoles);
    boolean requiresAcceptTc =
        termsAcceptanceService.requiresAcceptance(userType, identity.userGuid(), delegatedAdmin);

    return Requester.builder()
        .oidcUserId(identity.oidcUserId())
        .userName(identity.userName())
        .firstName(identity.firstName())
        .lastName(identity.lastName())
        .email(identity.email())
        .userType(userType)
        .userGuid(identity.userGuid())
        .businessGuid(identity.businessGuid())
        .accessRoles(accessRoles)
        .isDelegatedAdmin(delegatedAdmin)
        .requiresAcceptTc(requiresAcceptTc)
        .build();
  }
}
