package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.dto.FamForestClientDto;
import ca.bc.gov.nrs.fam.entity.FamForestClient;
import ca.bc.gov.nrs.fam.integration.ForestClientIntegrationService;
import ca.bc.gov.nrs.fam.repository.FamForestClientRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Port of {@code crud_forest_client.py} and {@code router_forest_client.py}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForestClientService {

  private final FamForestClientRepository forestClientRepository;
  private final ForestClientIntegrationService forestClientIntegrationService;

  /**
   * Get - or create - FAM's local record of a forest client.
   *
   * <p>FAM stores only the number; the name and status always come from the Forest
   * Client API. The row exists purely so roles can reference a client by foreign
   * key.
   */
  @Transactional
  public FamForestClient findOrCreate(String forestClientNumber, String requesterOidcId) {
    return forestClientRepository.findByForestClientNumber(forestClientNumber)
        .orElseGet(() -> {
          log.debug("Forest client {} not stored yet; adding", forestClientNumber);
          FamForestClient created = new FamForestClient();
          created.setForestClientNumber(forestClientNumber);
          created.setCreateUser(requesterOidcId);
          return forestClientRepository.save(created);
        });
  }

  /**
   * Search the Forest Client API by number.
   *
   * <p>Port of {@code router_forest_client.search}. The API only does exact
   * matching on a whole 8-digit number, so this returns at most one result despite
   * the list return type.
   */
  public List<FamForestClientDto> search(
      String clientNumber, ApiInstanceEnv apiInstanceEnv) {

    log.debug("Searching Forest Clients with client_number: {}", clientNumber);

    List<Map<String, Object>> apiResults = forestClientIntegrationService.search(
        List.of(clientNumber), apiInstanceEnv, false);

    List<FamForestClientDto> forestClients = apiResults.stream()
        .map(ForestClientEnrichmentService::toForestClientDto)
        .toList();

    log.debug("Returning {} result(s)", forestClients.size());
    return forestClients;
  }
}
