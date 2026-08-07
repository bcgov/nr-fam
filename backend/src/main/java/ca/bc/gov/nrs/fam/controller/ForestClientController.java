package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.dto.FamForestClientDto;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.service.ApiInstanceEnvResolver;
import ca.bc.gov.nrs.fam.service.ApplicationService;
import ca.bc.gov.nrs.fam.service.ForestClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Port of {@code router_forest_client.py}. */
@Validated
@RestController
@RequestMapping("/forest-clients")
@Tag(name = "FAM Forest Clients")
@RequiredArgsConstructor
public class ForestClientController {

  private final ForestClientService forestClientService;
  private final ApplicationService applicationService;
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;
  private final AuthorizationService authorizationService;

  /**
   * Look up a forest client by number.
   *
   * <p>The Forest Client API only matches a whole 8-digit number exactly, so at
   * most one result comes back despite the list return type.
   *
   * @param applicationId which application's environment decides whether the TEST
   *     or PROD instance of the Forest Client API is used
   */
  @GetMapping("/search")
  @Operation(operationId = "search", summary = "Search forest clients by client number")
  public List<FamForestClientDto> search(
      @RequestParam @Size(min = 3, max = 8) String clientNumber,
      @RequestParam Long applicationId,
      Requester requester) {

    // General check: the caller must administer at least one application.
    authorizationService.authorize(requester);

    FamApplication application = applicationService.requireApplication(applicationId);
    ApiInstanceEnv apiInstanceEnv = apiInstanceEnvResolver.resolve(application);

    return forestClientService.search(clientNumber, apiInstanceEnv);
  }
}
