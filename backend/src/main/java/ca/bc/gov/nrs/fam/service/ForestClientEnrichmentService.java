package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.dto.FamApplicationUserRoleAssignmentGetDto;
import ca.bc.gov.nrs.fam.dto.FamForestClientDto;
import ca.bc.gov.nrs.fam.dto.FamRoleWithClientDto;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import ca.bc.gov.nrs.fam.integration.ForestClientIntegrationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Fills in forest client names on a set of user-role assignments.
 *
 * <p>Port of {@code post_sync_forest_clients_dec}. FAM stores only client
 * numbers, so names come from the Forest Client API at read time.
 *
 * <p>This <strong>soft-fails</strong>. The Forest Client API's TEST instance is
 * unreliable, and a permissions listing is still useful with client numbers but
 * no names - so a timeout or connection failure logs a warning and returns the
 * assignments unchanged rather than failing the request. Any other upstream
 * error still propagates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForestClientEnrichmentService {

  private static final Set<String> SOFT_FAIL_CODES =
      Set.of(ErrorCode.UPSTREAM_TIMEOUT, ErrorCode.UPSTREAM_CONNECTION_ERROR);

  private final ForestClientIntegrationService forestClientIntegrationService;
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;

  /**
   * @return the same assignments, with {@code client_name} populated where the
   *     Forest Client API knew the number. Assignments whose role has no forest
   *     client scope are returned untouched.
   */
  public List<FamApplicationUserRoleAssignmentGetDto> withClientNames(
      List<FamApplicationUserRoleAssignmentGetDto> assignments, FamApplication application) {

    if (assignments.isEmpty()) {
      return assignments;
    }

    // Duplicates are harmless - the API de-duplicates - so they are not filtered.
    List<String> clientNumbers = assignments.stream()
        .map(FamApplicationUserRoleAssignmentGetDto::role)
        .filter(role -> role != null && role.forestClient() != null)
        .map(role -> role.forestClient().forestClientNumber())
        .filter(java.util.Objects::nonNull)
        .toList();

    if (clientNumbers.isEmpty()) {
      return assignments;
    }

    Map<String, String> namesByNumber = lookupNames(clientNumbers, application);
    if (namesByNumber.isEmpty()) {
      return assignments;
    }

    return assignments.stream().map(item -> applyName(item, namesByNumber)).toList();
  }

  private Map<String, String> lookupNames(List<String> clientNumbers, FamApplication application) {
    ApiInstanceEnv apiInstanceEnv = apiInstanceEnvResolver.resolve(application);

    try {
      // Retry enabled: this runs behind a page load where one extra attempt is
      // cheaper than showing numbers without names.
      List<Map<String, Object>> results = forestClientIntegrationService.search(
          clientNumbers, clientNumbers.size(), apiInstanceEnv, true);

      Map<String, String> namesByNumber = new LinkedHashMap<>();
      for (Map<String, Object> result : results) {
        Object number = result.get("clientNumber");
        Object name = result.get("clientName");
        if (number != null) {
          namesByNumber.put(String.valueOf(number), name == null ? null : String.valueOf(name));
        }
      }
      return namesByNumber;

    } catch (UpstreamException e) {
      if (SOFT_FAIL_CODES.contains(e.getFailureCode())) {
        log.warn("Forest Client API search failed ({}) after retry. "
            + "Skipping client name enrichment.", e.getFailureCode(), e);
        return Map.of();
      }
      throw e;
    }
  }

  private FamApplicationUserRoleAssignmentGetDto applyName(
      FamApplicationUserRoleAssignmentGetDto item, Map<String, String> namesByNumber) {

    FamRoleWithClientDto role = item.role();
    if (role == null || role.forestClient() == null) {
      return item;
    }

    FamForestClientDto forestClient = role.forestClient();
    // A number the API did not return maps to null, matching upstream - the row
    // is still shown, just without a name.
    FamForestClientDto named = new FamForestClientDto(
        namesByNumber.get(forestClient.forestClientNumber()),
        forestClient.forestClientNumber(),
        forestClient.status());

    FamRoleWithClientDto namedRole = new FamRoleWithClientDto(
        role.roleId(), role.roleName(), role.roleTypeCode(), role.displayName(),
        role.description(), role.application(), named, role.parentRole());

    return new FamApplicationUserRoleAssignmentGetDto(
        item.userRoleXrefId(), item.userId(), item.roleId(), item.user(), namedRole,
        item.createDate(), item.expiryDate());
  }

  /** Maps one Forest Client API result item onto FAM's client shape. */
  public static FamForestClientDto toForestClientDto(Map<String, Object> apiResult) {
    Object number = apiResult.get("clientNumber");
    Object name = apiResult.get("clientName");
    Object statusCode = apiResult.get(FamConstants.FOREST_CLIENT_STATUS_KEY);

    return new FamForestClientDto(
        name == null ? null : String.valueOf(name),
        number == null ? null : String.valueOf(number),
        ca.bc.gov.nrs.fam.dto.FamForestClientStatusDto.fromApiStatusCode(
            statusCode == null ? null : String.valueOf(statusCode)));
  }
}
