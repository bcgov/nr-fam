package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.FamConstants;
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
   * organisation knows its name far more often than its eight-digit number.
   *
   * <p>The term is matched as a <b>substring of the name or of the number</b>,
   * so "eren" finds SERENPET and "58846" finds 00058846. Nothing is classified
   * as one kind of term or the other beforehand: digits appear in company names
   * too, and the upstream matches both fields in a single query.
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

    // One rule, whatever was typed: show the active organisations whose name or
    // number contains it, closest match first. The upstream now does the
    // containment itself, so refining is only ordering the answer and covering
    // the acronym arm, which matches on a field the substring query does not.
    List<Map<String, Object>> candidates = gather(trimmed, apiInstanceEnv);

    return refine(candidates, trimmed).stream()
        .filter(ForestClientController::isActive)
        .limit(AUTOCOMPLETE_LIMIT)
        .map(ForestClientController::toDto)
        .toList();
  }

  /**
   * Every row the upstream will offer for this term, deduplicated.
   *
   * <p>The substring search covers name and number together in one call. The
   * acronym search is added only when the term could be an acronym, because it
   * costs a second round trip on every keystroke pause and can only ever answer
   * a fully-typed one.
   */
  private List<Map<String, Object>> gather(String term, ApiInstanceEnv apiInstanceEnv) {
    Map<String, Map<String, Object>> byNumber = new java.util.LinkedHashMap<>();

    collect(byNumber, forestClientIntegrationService.searchByNumberOrName(
        term, AUTOCOMPLETE_FETCH, apiInstanceEnv));

    if (couldBeAcronym(term)) {
      collect(byNumber, forestClientIntegrationService.searchByAcronym(
          term, AUTOCOMPLETE_FETCH, apiInstanceEnv));
    }

    return List.copyOf(byNumber.values());
  }

  /** Acronyms are short, unspaced and alphabetic; anything else cannot match one. */
  private static boolean couldBeAcronym(String term) {
    return term.length() <= MAX_ACRONYM_LENGTH && term.chars().allMatch(Character::isLetter);
  }

  /** Keyed by client number, so the same client found twice is listed once. */
  private static void collect(
      Map<String, Map<String, Object>> into, List<Map<String, Object>> rows) {
    rows.forEach(row -> into.putIfAbsent(text(row, "clientNumber"), row));
  }

  /** Enough rows to choose from without turning the list into a second search. */
  private static final int AUTOCOMPLETE_LIMIT = 10;

  /**
   * How many rows to ask for before refining.
   *
   * <p>More than are shown, because the upstream returns them in client-number
   * order rather than by relevance: asking for ten and filtering would often
   * leave nothing, having thrown away the one row that matched.
   */
  private static final int AUTOCOMPLETE_FETCH = 50;

  /**
   * Order what came back: matches that <em>start</em> with the term come first.
   *
   * <p>Ordering, not filtering - the upstream already matched on containment, and
   * a row reached by the acronym arm is kept by the acronym check here. Nothing
   * that legitimately matched is dropped.
   *
   * <p>It sorts because the upstream returns rows in client-number order rather
   * than by relevance, so without this "BC" leads with whichever containing name
   * happens to hold the lowest number rather than with the names that begin
   * "BC".
   */
  private static List<Map<String, Object>> refine(
      List<Map<String, Object>> results, String term) {

    String needle = term.toUpperCase(java.util.Locale.ROOT);

    List<Map<String, Object>> startsWith = new java.util.ArrayList<>();
    List<Map<String, Object>> contains = new java.util.ArrayList<>();

    for (Map<String, Object> result : results) {
      String name = text(result, "clientName").toUpperCase(java.util.Locale.ROOT);
      String number = text(result, "clientNumber").toUpperCase(java.util.Locale.ROOT);
      String acronym = text(result, "acronym").toUpperCase(java.util.Locale.ROOT);

      if (name.startsWith(needle) || number.startsWith(needle)
          || acronym.startsWith(needle)) {
        startsWith.add(result);
      } else if (name.contains(needle) || number.contains(needle)
          || acronym.contains(needle)) {
        contains.add(result);
      }
      // Anything reaching neither branch matched on a field not shown - it is
      // dropped rather than offered as a result nobody can account for.
    }

    List<Map<String, Object>> refined = new java.util.ArrayList<>(startsWith);
    refined.addAll(contains);
    return refined;
  }

  /** The longest acronym the upstream holds. */
  private static final int MAX_ACRONYM_LENGTH = 8;

  /**
   * Only organisations that can actually be granted.
   *
   * <p>An inactive one is findable upstream but refused on selection, so
   * offering it is offering a dead end. Filtered after refining so an inactive
   * near-match cannot take a slot from an active one.
   */
  private static boolean isActive(Map<String, Object> result) {
    return FamConstants.FOREST_CLIENT_STATUS_CODE_ACTIVE
        .equals(text(result, "clientStatusCode"));
  }

  private static String text(Map<String, Object> result, String key) {
    Object value = result.get(key);
    return value == null ? "" : String.valueOf(value);
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
