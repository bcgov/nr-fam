package ca.bc.gov.nrs.fam.controller;

import org.springdoc.core.annotations.ParameterObject;
import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.IdimSearchUserParamType;
import ca.bc.gov.nrs.fam.dto.IdimIdirUsersSearchParams;
import ca.bc.gov.nrs.fam.dto.IdimProxyBceidInfoDto;
import ca.bc.gov.nrs.fam.dto.IdimProxyIdirInfoDto;
import ca.bc.gov.nrs.fam.dto.IdimProxyIdirUsersSearchResultDto;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.IdimProxyService;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.service.ApiInstanceEnvResolver;
import ca.bc.gov.nrs.fam.service.ApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Port of {@code router_idim_proxy.py}. */
@Slf4j
@Validated
@RestController
@RequestMapping("/identity-lookup")
@Tag(name = "IDIR/BCeID Proxy")
@RequiredArgsConstructor
public class IdentityLookupController {

  private final IdimProxyService idimProxyService;
  private final ApplicationService applicationService;
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;
  private final AuthorizationService authorizationService;

  /**
   * Look up an IDIR user.
   *
   * <p>IDIR-only: an external (BCeID) administrator has no business enumerating
   * government staff accounts.
   */
  @GetMapping("/idir")
  @Operation(operationId = "idir_lookup", summary = "Lookup IDIR user", description = "Lookup an IDIR user by user ID.")
  public IdimProxyIdirInfoDto lookupIdir(
      @RequestParam @Size(max = 20) String userId,
      @RequestParam Long applicationId,
      Requester requester) {

    authorizationService.authorize(requester);
    authorizationService.internalOnlyAction(requester);

    return idimProxyService.lookupIdir(userId, requester, instanceFor(applicationId));
  }

  /**
   * Look up a Business BCeID user.
   *
   * <p>Open to both IDIR and BCeID administrators; a BCeID caller is confined to
   * their own organisation by the integration itself.
   */
  @GetMapping("/bceid")
  @Operation(operationId = "bceid_lookup", summary = "Lookup BCEID user",
      description = "Lookup a BCeID Business user by user ID.")
  public IdimProxyBceidInfoDto lookupBceid(
      @RequestParam @Size(max = 20) String userId,
      @RequestParam Long applicationId,
      Requester requester) {

    authorizationService.authorize(requester);

    return idimProxyService.lookupBusinessBceid(
        IdimSearchUserParamType.USER_ID, userId, requester, instanceFor(applicationId));
  }

  @GetMapping("/users/idir/search")
  @Operation(operationId = "search_idir_users", summary = "Search IDIR users", description = "Search for IDIR users.")
  public IdimProxyIdirUsersSearchResultDto searchIdirUsers(
      @ParameterObject @Valid IdimIdirUsersSearchParams searchParams,
      @RequestParam Long applicationId,
      Requester requester) {

    authorizationService.authorize(requester);

    // Cross-field rules bean validation cannot express: at least one term, and
    // each term at least two characters.
    try {
      searchParams.validate();
    } catch (IllegalArgumentException e) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER, e.getMessage());
    }

    log.info("Searching IDIR users on behalf of requester {} (id={})",
        requester.userName(), requester.userId());

    return idimProxyService.searchIdirUsers(searchParams, requester, instanceFor(applicationId));
  }

  private ApiInstanceEnv instanceFor(Long applicationId) {
    FamApplication application = applicationService.requireApplication(applicationId);
    return apiInstanceEnvResolver.resolve(application);
  }
}
