package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.dto.ExtUserRoleMetadataResponse;
import ca.bc.gov.nrs.fam.dto.ExtUserRoleMetadataRoleDto;
import ca.bc.gov.nrs.fam.dto.ExtUserSearchPagedResults;
import ca.bc.gov.nrs.fam.dto.ExtUserSearchParams;
import ca.bc.gov.nrs.fam.dto.IdimIdirUsersSearchParams;
import ca.bc.gov.nrs.fam.dto.IdimProxyIdirUsersSearchResultDto;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.entity.FamUserRoleXref;
import ca.bc.gov.nrs.fam.integration.IdimProxyService;
import ca.bc.gov.nrs.fam.repository.FamUserRoleXrefRepository;
import ca.bc.gov.nrs.fam.security.ExternalApiAuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.service.ApiInstanceEnvResolver;
import ca.bc.gov.nrs.fam.service.ExtAppUserSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FAM's external API, for downstream applications rather than FAM's own console.
 *
 * <p>Port of {@code routers/ext/router_user.py}. Two things set this surface
 * apart from the rest of the API and are deliberate:
 *
 * <ul>
 *   <li><strong>camelCase</strong> field names, where the internal API is
 *       snake_case. Both are published contracts.
 *   <li>It is exempt from the FAM-client token check, and authorised instead by
 *       {@code call_api_flag} on the caller's roles - see
 *       {@link ExternalApiAuthorizationService}.
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/external/v1/users")
@Tag(name = "FAM External API")
@RequiredArgsConstructor
public class ExternalUserController {

  private final ExtAppUserSearchService userSearchService;
  private final ExternalApiAuthorizationService externalApiAuthorizationService;
  private final IdimProxyService idimProxyService;
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;
  private final FamUserRoleXrefRepository userRoleXrefRepository;

  /**
   * Search the users of the calling application.
   *
   * <p>Spec: https://apps.nrs.gov.bc.ca/int/confluence/display/FSAST1/Users+Search+API
   */
  @GetMapping
  @Operation(operationId = "user_search", summary = "Search FAM users",
      description = "Search FAM users associated with the calling application.")
  public ExtUserSearchPagedResults userSearch(
      @ParameterObject @Valid ExtUserSearchParams params,
      @AuthenticationPrincipal Jwt jwt,
      Requester requester) {

    FamApplication application = externalApiAuthorizationService.authorize(jwt, requester);

    log.debug("External user search by {} for application {}",
        requester.userName(), application.getApplicationName());

    return userSearchService.searchUsers(application.getApplicationId(), params);
  }

  /**
   * Search IDIR users through the IDIM proxy.
   *
   * <p>Deprecated upstream: this searches the government directory, not FAM, and
   * callers should use their own IDIM integration. Kept so existing consumers do
   * not break.
   */
  @Deprecated
  @GetMapping("/identity/idir/search")
  @Operation(operationId = "search_idim_idir_users", summary = "Search IDIR users",
      description = "Search IDIR user identities through IDIM.", deprecated = true)
  public IdimProxyIdirUsersSearchResultDto searchIdirUsers(
      @ParameterObject @Valid IdimIdirUsersSearchParams searchParams,
      @AuthenticationPrincipal Jwt jwt,
      Requester requester) {

    FamApplication application = externalApiAuthorizationService.authorize(jwt, requester);

    searchParams.validate();

    ApiInstanceEnv apiInstanceEnv = apiInstanceEnvResolver.resolve(application);
    return idimProxyService.searchIdirUsers(searchParams, requester, apiInstanceEnv);
  }

  /**
   * The caller's own roles within the calling application.
   *
   * <p>Lets a downstream application ask FAM what the signed-in user may do,
   * rather than needing FAM's roles inside its own token - which is what the
   * Cognito trigger used to provide and Keycloak cannot.
   *
   * <p>Deliberately not gated on {@code call_api_flag}: a user asking about their
   * own access should not need a special role to get an answer.
   */
  @GetMapping("/me/role-metadata")
  @Operation(operationId = "get_current_user_role_metadata",
      summary = "Get current user role metadata",
      description = "Roles for the authenticated user within the calling application.")
  public ExtUserRoleMetadataResponse getCurrentUserRoleMetadata(
      @AuthenticationPrincipal Jwt jwt, Requester requester) {

    FamApplication application = externalApiAuthorizationService.resolveApplication(jwt);

    List<FamUserRoleXref> assignments = userRoleXrefRepository.findByUserAndApplication(
        requester.userId(), application.getApplicationId());

    List<ExtUserRoleMetadataRoleDto> roles = assignments.stream()
        .map(assignment -> new ExtUserRoleMetadataRoleDto(
            assignment.getRole().getRoleName(),
            assignment.getRole().getDisplayName(),
            // Truncated to whole seconds, as upstream did - sub-second precision
            // is noise in a published contract.
            assignment.getExpiryDate() == null
                ? null
                : assignment.getExpiryDate().withNano(0),
            assignment.getRole().getForestClient() == null
                ? null
                : assignment.getRole().getForestClient().getForestClientNumber()))
        .toList();

    return new ExtUserRoleMetadataResponse(
        requester.userName(),
        ExtAppUserSearchService.toIdpType(
            requester.userType() == null ? null : requester.userType().getCode()),
        roles);
  }
}
