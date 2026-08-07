package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.service.UserTermsConditionsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Port of {@code router_user_terms_conditions.py}. */
@RestController
@RequestMapping("/user-terms-conditions")
@Tag(name = "FAM User Terms and Conditions")
@RequiredArgsConstructor
public class UserTermsConditionsController {

  private final UserTermsConditionsService userTermsConditionsService;
  private final AuthorizationService authorizationService;

  /**
   * Whether the caller still has to accept the current terms.
   *
   * <p>A POST with no body, and a bare boolean response - preserved from upstream
   * because the frontend calls it that way.
   */
  @PostMapping("/user:validate")
  @Operation(operationId = "validate_user_requires_accept_terms_and_conditions", summary = "Whether the requester must accept the terms and conditions")
  public boolean validateRequiresAcceptTermsAndConditions(Requester requester) {
    return requester.requiresAcceptTc();
  }

  @PostMapping
  @Operation(operationId = "create_user_terms_and_conditions", summary = "Record acceptance of the current terms and conditions")
  public void acceptTermsAndConditions(Requester requester) {
    // Only an external (BCeID) delegated admin is ever asked to accept.
    authorizationService.externalDelegatedAdminOnlyAction(requester);
    userTermsConditionsService.acceptCurrentTerms(requester.userId(), requester.oidcUserId());
  }
}
