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

  /**
   * Autocomplete a forest client by number or by name.
   *
   * <p>One field, either kind of term - which is the point. A person adding an
   * organisation knows its name far more often than its eight-digit number, and
   * the number search matches whole numbers exactly, so a partial number found
   * nothing and a name found nothing either.
   *
   * <p><b>A numeric term is zero-padded and searched as a number</b>; anything
   * else is searched as a name. The upstream ANDs its criteria rather than ORing
   * them, so the two cannot be sent together and the term has to be classified
   * first. "1011" therefore finds client 00001011, not an organisation with 1011
   * in its name.
   *
   * <p>Three characters minimum, so a single keystroke does not fan out into a
   * search returning most of the province.
   */
  @GetMapping("/autocomplete")
  @Operation(operationId = "autocomplete_forest_clients",
      summary = "Autocomplete forest clients by client number or name")
  public List<FamForestClientDto> autocomplete(
      @RequestParam @Size(min = 3, max = 60) String term,
      @RequestParam String environment,
      Requester requester) {

    authorizationService.authorize(requester);

    ApiInstanceEnv apiInstanceEnv = apiInstanceEnvResolver.resolve(environment);
    String trimmed = term.trim();

    List<Map<String, Object>> results = trimmed.chars().allMatch(Character::isDigit)
        // The number column is eight characters wide and matched whole, so a
        // shorter number has to be padded to stand a chance of matching.
        ? forestClientIntegrationService.search(
            List.of(padClientNumber(trimmed)), AUTOCOMPLETE_LIMIT, apiInstanceEnv, false)
        : forestClientIntegrationService.searchByName(
            trimmed, AUTOCOMPLETE_LIMIT, apiInstanceEnv);

    return results.stream().map(ForestClientController::toDto).toList();
  }

  /** Enough rows to choose from without turning the list into a second search. */
  private static final int AUTOCOMPLETE_LIMIT = 10;

  private static String padClientNumber(String number) {
    return number.length() >= 8 ? number : "0".repeat(8 - number.length()) + number;
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
