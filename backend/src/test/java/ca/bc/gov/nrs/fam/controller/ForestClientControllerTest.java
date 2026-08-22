package ca.bc.gov.nrs.fam.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.FamForestClientDto;
import ca.bc.gov.nrs.fam.integration.ForestClientIntegrationService;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.service.ApiInstanceEnvResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Gathering and ordering the forest-client autocomplete.
 *
 * <p>The substring endpoint does the matching now, across name and number in one
 * query, so what is left here is the acronym arm, the ordering - the upstream
 * returns rows in client-number order rather than by relevance - and dropping
 * the inactive.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ForestClientController (autocomplete)")
class ForestClientControllerTest {

  @Mock private ForestClientIntegrationService forestClientIntegrationService;
  @Mock private AuthorizationService authorizationService;
  @Mock private ApiInstanceEnvResolver apiInstanceEnvResolver;

  private ForestClientController controller;

  private final Requester requester =
      Requester.builder().userName("JSMITH").userType(UserType.IDIR).userGuid("AAAA").build();

  @BeforeEach
  void setUp() {
    controller = new ForestClientController(
        forestClientIntegrationService, apiInstanceEnvResolver, authorizationService);
    when(apiInstanceEnvResolver.resolve(anyString())).thenReturn(ApiInstanceEnv.TEST);
  }

  private static Map<String, Object> client(String name, String number) {
    return client(name, number, "ACT");
  }

  private static Map<String, Object> client(String name, String number, String status) {
    return Map.of("clientName", name, "clientNumber", number, "clientStatusCode", status);
  }

  private void upstreamReturns(Map<String, Object>... rows) {
    when(forestClientIntegrationService.searchByNumberOrName(anyString(), anyInt(), any()))
        .thenReturn(List.of(rows));
  }

  @Test
  @DisplayName("keeps a match in the middle of a name, not just a prefix")
  void containsNotJustStartsWith() {
    upstreamReturns(client("SERENPET INC.", "00058846"));

    assertThat(controller.autocomplete("eren", "dev", requester))
        .extracting(FamForestClientDto::clientName)
        .containsExactly("SERENPET INC.");
  }

  @Test
  @DisplayName("finds a client by part of its number, unpadded")
  void matchesPartialNumber() {
    // "58846" is what people quote; "00058846" is what is stored. A substring
    // match spans the two, so no zero-padding is guessed at.
    upstreamReturns(client("SERENPET INC.", "00058846"));

    assertThat(controller.autocomplete("58846", "dev", requester))
        .extracting(FamForestClientDto::forestClientNumber)
        .containsExactly("00058846");
  }

  @Test
  @DisplayName("a run of zeros matches the numbers that contain it")
  void matchesLeadingZeros() {
    // This was the reported bug: "000" returned nothing, because the endpoint
    // then in use matched the number with = rather than LIKE.
    upstreamReturns(
        client("FIRST", "00001011"),
        client("SECOND", "00058846"));

    assertThat(controller.autocomplete("000", "dev", requester))
        .extracting(FamForestClientDto::forestClientNumber)
        .containsExactly("00001011", "00058846");
  }

  @Test
  @DisplayName("puts a name that starts with the term above one that merely contains it")
  void ranksPrefixMatchesFirst() {
    // The upstream orders by client number, so without this the lower number
    // leads regardless of how well it matches.
    upstreamReturns(
        client("WEST ACME HOLDINGS", "00001011"),
        client("ACME FORESTRY LTD.", "00009999"));

    assertThat(controller.autocomplete("acme", "dev", requester))
        .extracting(FamForestClientDto::clientName)
        .containsExactly("ACME FORESTRY LTD.", "WEST ACME HOLDINGS");
  }

  @Test
  @DisplayName("drops a row that matches on no field it shows")
  void dropsUnaccountableRows() {
    upstreamReturns(
        client("ACME FORESTRY LTD.", "00001011"),
        client("REYBURN HOLDINGS", "00002022"));

    assertThat(controller.autocomplete("acme", "dev", requester))
        .extracting(FamForestClientDto::clientName)
        .containsExactly("ACME FORESTRY LTD.");
  }

  @Test
  @DisplayName("asks for more rows than it shows, and shows no more than ten")
  void limitsToTenButFetchesMore() {
    List<Map<String, Object>> many = new java.util.ArrayList<>();
    for (int i = 0; i < 30; i++) {
      many.add(client("SERENPET " + i, String.format("%08d", i)));
    }
    when(forestClientIntegrationService.searchByNumberOrName(anyString(), anyInt(), any()))
        .thenReturn(many);

    assertThat(controller.autocomplete("serenpet", "dev", requester)).hasSize(10);
    verify(forestClientIntegrationService)
        .searchByNumberOrName(eq("serenpet"), eq(50), eq(ApiInstanceEnv.TEST));
  }

  @Test
  @DisplayName("offers only organisations that can actually be granted")
  void filtersInactive() {
    upstreamReturns(
        client("ACME FORESTRY LTD.", "00001011", "ACT"),
        client("ACME DEFUNCT LTD.", "00002022", "DAC"));

    assertThat(controller.autocomplete("acme", "dev", requester))
        .extracting(FamForestClientDto::clientName)
        .containsExactly("ACME FORESTRY LTD.");
  }

  @Test
  @DisplayName("filters inactive after ranking, so it cannot cost an active one its place")
  void filtersInactiveAfterRanking() {
    List<Map<String, Object>> rows = new java.util.ArrayList<>();
    for (int i = 0; i < 12; i++) {
      rows.add(client("ACME " + i, String.format("%08d", i), "DAC"));
    }
    rows.add(client("ACME LAST", "00009999", "ACT"));
    when(forestClientIntegrationService.searchByNumberOrName(anyString(), anyInt(), any()))
        .thenReturn(rows);

    assertThat(controller.autocomplete("acme", "dev", requester))
        .extracting(FamForestClientDto::clientName)
        .containsExactly("ACME LAST");
  }

  @Test
  @DisplayName("looks up the acronym as well when the term could be one")
  void searchesAcronym() {
    // BCTS is not a substring of "BC TIMBER SALES", so the substring query
    // cannot find an organisation people know by its acronym.
    controller.autocomplete("bcts", "dev", requester);

    verify(forestClientIntegrationService)
        .searchByAcronym(eq("bcts"), anyInt(), any());
  }

  @Test
  @DisplayName("keeps what the acronym arm found, which matches on neither name nor number")
  void keepsAcronymMatches() {
    when(forestClientIntegrationService.searchByAcronym(anyString(), anyInt(), any()))
        .thenReturn(List.of(Map.of("clientName", "BC TIMBER SALES",
            "clientNumber", "00001011", "clientStatusCode", "ACT", "acronym", "BCTS")));

    assertThat(controller.autocomplete("bcts", "dev", requester))
        .extracting(FamForestClientDto::clientName)
        .containsExactly("BC TIMBER SALES");
  }

  @Test
  @DisplayName("spends no call on an acronym lookup a term cannot match")
  void skipsAcronymLookupForNonAcronyms() {
    // Exact match only, so a term with digits or a space can never answer one -
    // and this runs on every keystroke pause.
    controller.autocomplete("00058", "dev", requester);
    controller.autocomplete("acme forestry ltd", "dev", requester);

    verify(forestClientIntegrationService, org.mockito.Mockito.never())
        .searchByAcronym(anyString(), anyInt(), any());
  }

  @Test
  @DisplayName("the same client found by both arms is listed once")
  void deduplicates() {
    Map<String, Object> bcts = Map.of("clientName", "BCTS HOLDINGS",
        "clientNumber", "00001011", "clientStatusCode", "ACT", "acronym", "BCTS");
    upstreamReturns(bcts);
    when(forestClientIntegrationService.searchByAcronym(anyString(), anyInt(), any()))
        .thenReturn(List.of(bcts));

    assertThat(controller.autocomplete("bcts", "dev", requester)).hasSize(1);
  }

  @Test
  @DisplayName("no longer pads a number into a second exact lookup")
  void doesNotFallBackToPaddedLookup() {
    // The substring query spans padded and unpadded forms, so the three-shot
    // guessing at what the upstream stored is gone.
    upstreamReturns(client("SERENPET INC.", "00058846"));

    controller.autocomplete("58846", "dev", requester);

    verify(forestClientIntegrationService, org.mockito.Mockito.never())
        .search(any(), anyInt(), any(), anyBoolean());
  }
}
