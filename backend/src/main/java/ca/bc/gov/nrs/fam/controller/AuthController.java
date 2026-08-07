package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.dto.SelfDto;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.security.AccessRoleResolver;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.security.RequesterService;
import ca.bc.gov.nrs.fam.security.TokenClaimsReader;
import ca.bc.gov.nrs.fam.security.UserProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in bootstrap.
 *
 * <p>This endpoint is what replaces the AWS Cognito pre-token-generation Lambda
 * ({@code server/auth_function}). Cognito ran that trigger during login to create
 * the user's {@code fam_user} row and inject their roles into the token; a BC Gov
 * SSO realm cannot run application code at token time without a custom SPI.
 *
 * <p>So the work moves here. The frontend calls {@code POST /auth/login} once,
 * immediately after a successful Keycloak sign-in and before any other API call.
 * Until it does, a first-time user holds a valid token but has no FAM identity,
 * and every other endpoint answers {@code requester_not_exists}.
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication")
@RequiredArgsConstructor
public class AuthController {

  private final UserProvisioningService userProvisioningService;
  private final AccessRoleResolver accessRoleResolver;
  private final RequesterService requesterService;
  private final TokenClaimsReader claimsReader;

  /**
   * Provision the signed-in user and return their identity and effective roles.
   *
   * <p>Idempotent: safe to call on every sign-in and on token refresh. Each call
   * also refreshes the stored name and email from the identity provider.
   */
  @PostMapping("/login")
  @Operation(operationId = "login", summary = "Provision the signed-in user and return their access",
      description = "Called once after a successful Keycloak sign-in. Replaces the "
          + "Cognito pre-token-generation trigger.")
  public SelfDto login(@AuthenticationPrincipal Jwt jwt) {
    TokenClaimsReader.TokenIdentity identity = claimsReader.identity(jwt);

    FamUser famUser = userProvisioningService.provisionUser(identity);

    List<String> accessRoles = accessRoleResolver.resolveAccessRoles(
        identity.userGuid(), identity.userTypeCode(), claimsReader.appClientId(jwt));

    log.info("Login bootstrap for {} ({} role(s))", famUser.getUserName(), accessRoles.size());

    return toSelf(requesterService.forProvisionedUser(famUser, accessRoles));
  }

  /**
   * The caller's current identity and roles, without provisioning.
   *
   * <p>Roles are re-resolved from the database, so a revocation is reflected here
   * without the user signing in again.
   */
  @GetMapping("/self")
  @Operation(operationId = "self", summary = "Current user's identity and effective access roles")
  public SelfDto self(Requester requester) {
    return toSelf(requester);
  }

  private static SelfDto toSelf(Requester requester) {
    return new SelfDto(
        requester.userId(),
        requester.userName(),
        requester.userType() == null ? null : requester.userType().getCode(),
        requester.firstName(),
        requester.lastName(),
        requester.email(),
        requester.accessRoles(),
        requester.isDelegatedAdmin(),
        requester.requiresAcceptTc());
  }
}
