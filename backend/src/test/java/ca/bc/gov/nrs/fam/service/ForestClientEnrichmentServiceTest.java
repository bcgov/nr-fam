package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.dto.FamApplicationDto;
import ca.bc.gov.nrs.fam.dto.FamApplicationUserRoleAssignmentGetDto;
import ca.bc.gov.nrs.fam.dto.FamForestClientDto;
import ca.bc.gov.nrs.fam.dto.FamRoleWithClientDto;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import ca.bc.gov.nrs.fam.integration.ForestClientIntegrationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("ForestClientEnrichmentService (port of post_sync_forest_clients_dec)")
class ForestClientEnrichmentServiceTest {

  @Mock
  private ForestClientIntegrationService forestClientIntegrationService;

  @Mock
  private ApiInstanceEnvResolver apiInstanceEnvResolver;

  @InjectMocks
  private ForestClientEnrichmentService service;

  private static final FamApplicationDto APPLICATION_DTO =
      new FamApplicationDto(1L, "FOM_DEV", "Forest Operations Map");

  private static FamApplication application() {
    FamApplication application = new FamApplication();
    application.setApplicationId(1L);
    application.setApplicationName("FOM_DEV");
    application.setAppEnvironment("DEV");
    return application;
  }

  private static FamApplicationUserRoleAssignmentGetDto assignment(String forestClientNumber) {
    FamForestClientDto forestClient = forestClientNumber == null
        ? null
        : new FamForestClientDto(null, forestClientNumber, null);

    return new FamApplicationUserRoleAssignmentGetDto(1L, 2L, 3L, null,
        new FamRoleWithClientDto(3L, "FOM_REVIEWER", "C", "Reviewer", null,
            APPLICATION_DTO, forestClient, null),
        null, null);
  }

  @Test
  @DisplayName("fills in the client name from the API result")
  void populatesClientName() {
    when(apiInstanceEnvResolver.resolve(any())).thenReturn(ApiInstanceEnv.TEST);
    when(forestClientIntegrationService.search(anyList(), anyInt(), any(), anyBoolean()))
        .thenReturn(List.of(Map.of(
            "clientNumber", "00001011", "clientName", "AKIECA EXPLORERS LTD.")));

    List<FamApplicationUserRoleAssignmentGetDto> result =
        service.withClientNames(List.of(assignment("00001011")), application());

    assertThat(result).singleElement()
        .extracting(item -> item.role().forestClient().clientName())
        .isEqualTo("AKIECA EXPLORERS LTD.");
  }

  @Test
  @DisplayName("leaves the name null for a number the API did not return")
  void unmatchedNumberKeepsNullName() {
    when(apiInstanceEnvResolver.resolve(any())).thenReturn(ApiInstanceEnv.TEST);
    when(forestClientIntegrationService.search(anyList(), anyInt(), any(), anyBoolean()))
        .thenReturn(List.of(Map.of("clientNumber", "00001011", "clientName", "FOUND")));

    List<FamApplicationUserRoleAssignmentGetDto> result = service.withClientNames(
        List.of(assignment("00001011"), assignment("99999999")), application());

    assertThat(result.get(0).role().forestClient().clientName()).isEqualTo("FOUND");
    assertThat(result.get(1).role().forestClient().clientName()).isNull();
  }

  @Test
  @DisplayName("does not call the API when no role is scoped to a forest client")
  void skipsApiWhenNothingToEnrich() {
    List<FamApplicationUserRoleAssignmentGetDto> input = List.of(assignment(null));

    List<FamApplicationUserRoleAssignmentGetDto> result =
        service.withClientNames(input, application());

    assertThat(result).isSameAs(input);
    verify(forestClientIntegrationService, never())
        .search(anyList(), anyInt(), any(), anyBoolean());
  }

  @Test
  @DisplayName("does not call the API for an empty result set")
  void skipsApiWhenNoAssignments() {
    assertThat(service.withClientNames(List.of(), application())).isEmpty();
    verify(forestClientIntegrationService, never())
        .search(anyList(), anyInt(), any(), anyBoolean());
  }

  @Test
  @DisplayName("soft-fails on a timeout: names go null but the listing survives")
  void softFailsOnTimeout() {
    // The Forest Client API's TEST instance is unreliable; a permissions listing
    // is still useful with numbers but no names.
    when(apiInstanceEnvResolver.resolve(any())).thenReturn(ApiInstanceEnv.TEST);
    when(forestClientIntegrationService.search(anyList(), anyInt(), any(), anyBoolean()))
        .thenThrow(new UpstreamException(HttpStatus.GATEWAY_TIMEOUT,
            ErrorCode.UPSTREAM_TIMEOUT, "Upstream service timed out.", "forest-client-api"));

    List<FamApplicationUserRoleAssignmentGetDto> result =
        service.withClientNames(List.of(assignment("00001011")), application());

    assertThat(result).singleElement()
        .extracting(item -> item.role().forestClient().clientName())
        .isNull();
    assertThat(result.get(0).role().forestClient().forestClientNumber()).isEqualTo("00001011");
  }

  @Test
  @DisplayName("soft-fails on a connection error too")
  void softFailsOnConnectionError() {
    when(apiInstanceEnvResolver.resolve(any())).thenReturn(ApiInstanceEnv.TEST);
    when(forestClientIntegrationService.search(anyList(), anyInt(), any(), anyBoolean()))
        .thenThrow(new UpstreamException(HttpStatus.GATEWAY_TIMEOUT,
            ErrorCode.UPSTREAM_CONNECTION_ERROR, "Could not connect.", "forest-client-api"));

    assertThat(service.withClientNames(List.of(assignment("00001011")), application()))
        .hasSize(1);
  }

  @Test
  @DisplayName("propagates a non-connectivity upstream failure rather than hiding it")
  void propagatesOtherUpstreamErrors() {
    when(apiInstanceEnvResolver.resolve(any())).thenReturn(ApiInstanceEnv.TEST);
    when(forestClientIntegrationService.search(anyList(), anyInt(), any(), anyBoolean()))
        .thenThrow(new UpstreamException(HttpStatus.INTERNAL_SERVER_ERROR,
            "FC_BOOM", "upstream broke", "forest-client-api"));

    assertThatThrownBy(() -> service.withClientNames(
        List.of(assignment("00001011")), application()))
        .isInstanceOf(UpstreamException.class)
        .extracting("failureCode")
        .isEqualTo("FC_BOOM");
  }

  @Test
  @DisplayName("requests a page big enough to hold every number it asks about")
  void requestsPageSizeMatchingLookupCount() {
    when(apiInstanceEnvResolver.resolve(any())).thenReturn(ApiInstanceEnv.TEST);
    when(forestClientIntegrationService.search(anyList(), anyInt(), any(), anyBoolean()))
        .thenReturn(List.of());

    service.withClientNames(
        List.of(assignment("00001011"), assignment("00001012"), assignment("00001013")),
        application());

    verify(forestClientIntegrationService)
        .search(List.of("00001011", "00001012", "00001013"), 3, ApiInstanceEnv.TEST, true);
  }
}
