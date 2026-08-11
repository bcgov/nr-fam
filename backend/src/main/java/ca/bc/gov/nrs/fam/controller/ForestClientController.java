package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.dto.FamForestClientDto;
import ca.bc.gov.nrs.fam.dto.FamForestClientStatusDto;
import ca.bc.gov.nrs.fam.integration.ForestClientIntegrationService;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.service.ApiInstanceEnvResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Forest client lookup, for scoping a role to one or more clients.
 *
 * <p>FAM no longer stores forest clients - the local table went with the role
 * tables in V94, and a client-scoped grant now carries the client number in the
 * CSS role name. What remains is this read-through to the Forest Client API, so
 * an administrator can find and verify a client number before granting on it.
 *
 * <p>Keyed on the CSS environment rather than a FAM application id: that is what
 * decides whether the TEST or PROD instance of the upstream API is used.
 */
@Validated
@RestController
@RequestMapping("/forest-clients")
@Tag(name = "FAM Forest Clients")
@RequiredArgsConstructor
public class ForestClientController {

  private final ForestClientIntegrationService forestClientIntegrationService;
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;
  private final AuthorizationService authorizationService;

  /**
   * Look up a forest client by number.
   *
   * <p>The Forest Client API only matches a whole 8-digit number exactly, so at
   * most one result comes back despite the list return type.
   *
   * @param environment the CSS environment of the application being administered,
   *     which decides whether the TEST or PROD upstream instance is used
   */
  @GetMapping("/search")
  @Operation(operationId = "search", summary = "Search forest clients by client number")
  public List<FamForestClientDto> search(
      @RequestParam @Size(min = 3, max = 8) String clientNumber,
      @RequestParam String environment,
      Requester requester) {

    authorizationService.authorize(requester);

    ApiInstanceEnv apiInstanceEnv = apiInstanceEnvResolver.resolve(environment);

    // No retry: this is user-facing, so latency matters more than a second try.
    return forestClientIntegrationService.search(List.of(clientNumber), apiInstanceEnv, false)
        .stream()
        .map(ForestClientController::toDto)
        .toList();
  }

  private static FamForestClientDto toDto(Map<String, Object> result) {
    String statusCode = result.get("clientStatusCode") == null
        ? null
        : String.valueOf(result.get("clientStatusCode"));

    FamForestClientStatusDto status = statusCode == null
        ? null
        : FamForestClientStatusDto.fromApiStatusCode(statusCode);

    return new FamForestClientDto(
        result.get("clientName") == null ? null : String.valueOf(result.get("clientName")),
        String.valueOf(result.get("clientNumber")),
        status);
  }
}
