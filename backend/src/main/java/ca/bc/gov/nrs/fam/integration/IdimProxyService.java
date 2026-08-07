package ca.bc.gov.nrs.fam.integration;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.ApiInstanceEnv;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.IdimSearchUserParamType;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.IdimIdirUsersSearchParams;
import ca.bc.gov.nrs.fam.dto.IdimProxyBceidInfoDto;
import ca.bc.gov.nrs.fam.dto.IdimProxyIdirInfoDto;
import ca.bc.gov.nrs.fam.dto.IdimProxyIdirUsersSearchResultDto;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import ca.bc.gov.nrs.fam.security.Requester;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Client for the IDIM proxy, which fronts the BC government IDIR/BCeID web
 * services.
 *
 * <p>Port of {@code integration/idim_proxy.py}. One API key covers every
 * environment, so only the base URL varies by {@link ApiInstanceEnv}.
 *
 * <p>Every call carries {@code requesterUserGuid}: IDIM audits lookups against
 * the person who made them, not against FAM as a service account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdimProxyService {

  private static final String UPSTREAM = "idim-proxy";

  /** IDIM's own vocabulary for the requester's account type. */
  private static final Map<UserType, String> ACCOUNT_TYPE = Map.of(
      UserType.IDIR, "Internal",
      UserType.BCEID, "Business");

  private final FamProperties famProperties;
  private final RestClientFactory restClientFactory;
  private final UpstreamErrorTranslator errorTranslator;
  private final ObjectMapper objectMapper;

  private final Map<ApiInstanceEnv, RestClient> clients = new EnumMap<>(ApiInstanceEnv.class);

  @PostConstruct
  void initClients() {
    FamProperties.Integration.IdimProxy config = famProperties.integration().idimProxy();

    for (ApiInstanceEnv env : ApiInstanceEnv.values()) {
      FamProperties.Integration.IdimProxy.Instance instance =
          env == ApiInstanceEnv.PROD ? config.prod() : config.test();

      if (instance == null || instance.baseUrl() == null || instance.baseUrl().isBlank()) {
        log.info("IDIM proxy {} instance not configured", env);
        continue;
      }

      clients.put(env, restClientFactory.create(
          instance.baseUrl() + "/api/idim-webservice",
          config.timeouts().connect(),
          config.timeouts().read(),
          headers -> {
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("X-API-KEY", config.apiKey());
          }));
    }
  }

  /** Exact-match lookup of a single IDIR user. The proxy does not do partial matching here. */
  public IdimProxyIdirInfoDto lookupIdir(
      String userId, Requester requester, ApiInstanceEnv apiInstanceEnv) {

    String uri = UriComponentsBuilder.fromPath("/idir-account-detail")
        .queryParam("userId", userId)
        .queryParam("requesterUserGuid", requester.userGuid())
        .build().toUriString();

    log.info("IDIM lookup_idir for userId={}", userId);
    return get(apiInstanceEnv, uri, IdimProxyIdirInfoDto.class);
  }

  /**
   * Look up a single Business BCeID user, by user id or GUID.
   *
   * <p>Enforces the same-organisation rule inline: a BCeID requester may not read
   * a user outside their own business. This lives here rather than in a guard
   * because the organisation is only known once IDIM has answered.
   *
   * @throws FamHttpException 403 {@code permission_required_for_operation} when a
   *     BCeID requester looks up a user from another organisation.
   */
  public IdimProxyBceidInfoDto lookupBusinessBceid(
      IdimSearchUserParamType searchBy, String searchValue, Requester requester,
      ApiInstanceEnv apiInstanceEnv) {

    String accountType = ACCOUNT_TYPE.get(requester.userType());
    if (accountType == null) {
      // BCSC users have no IDIM account type and cannot perform lookups.
      throw FamHttpException.internalError(ErrorCode.MISSING_KEY_ATTRIBUTE,
          "Requester user type " + requester.userType() + " cannot be mapped to an IDIM "
              + "account type.");
    }

    String uri = UriComponentsBuilder.fromPath("/businessBceid")
        .queryParam("searchUserBy", searchBy.getValue())
        .queryParam("searchValue", searchValue)
        .queryParam("requesterUserGuid", requester.userGuid())
        .queryParam("requesterAccountTypeCode", accountType)
        .build().toUriString();

    log.info("IDIM lookup_business_bceid by {}", searchBy.getValue());
    IdimProxyBceidInfoDto result = get(apiInstanceEnv, uri, IdimProxyBceidInfoDto.class);

    if (result.found()
        && requester.userType() == UserType.BCEID
        && !java.util.Objects.equals(requester.businessGuid(), result.businessGuid())) {
      throw FamHttpException.forbidden(ErrorCode.PERMISSION_REQUIRED,
          "Operation requires business bceid users to be within the same organization");
    }

    return result;
  }

  /**
   * Search IDIR users with partial matching.
   *
   * <p>A POST, not a GET: the requester GUID travels in the body while the search
   * terms are query parameters, which is how the proxy defines the endpoint.
   */
  public IdimProxyIdirUsersSearchResultDto searchIdirUsers(
      IdimIdirUsersSearchParams searchParams, Requester requester, ApiInstanceEnv apiInstanceEnv) {

    UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/idir-users/search");
    searchParams.toQueryParams().forEach(uri::queryParam);

    long start = System.nanoTime();
    try {
      RestClient client = clientFor(apiInstanceEnv);
      ResponseEntity<byte[]> response = client.post()
          .uri(uri.build().toUriString())
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("requesterUserGuid", requester.userGuid()))
          .retrieve()
          .toEntity(byte[].class);

      return handleResponse(response, IdimProxyIdirUsersSearchResultDto.class);
    } catch (ResourceAccessException e) {
      throw errorTranslator.connectivityFailure(UPSTREAM, e);
    } finally {
      log.info("IDIM search_idir_users completed in {} ms",
          (System.nanoTime() - start) / 1_000_000);
    }
  }

  private <T> T get(ApiInstanceEnv apiInstanceEnv, String uri, Class<T> type) {
    try {
      ResponseEntity<byte[]> response = clientFor(apiInstanceEnv).get()
          .uri(uri)
          .retrieve()
          .toEntity(byte[].class);
      return handleResponse(response, type);
    } catch (ResourceAccessException e) {
      throw errorTranslator.connectivityFailure(UPSTREAM, e);
    }
  }

  private <T> T handleResponse(ResponseEntity<byte[]> response, Class<T> type) {
    if (response.getStatusCode().isError()) {
      throw errorTranslator.httpError(UPSTREAM, response.getStatusCode(), response.getBody(),
          HttpStatus.valueOf(response.getStatusCode().value()).getReasonPhrase());
    }
    byte[] body = response.getBody();
    if (body == null || body.length == 0) {
      throw new UpstreamException(HttpStatus.BAD_GATEWAY, null,
          "Empty response from IDIM proxy.", UPSTREAM);
    }
    try {
      return objectMapper.readValue(body, type);
    } catch (IOException e) {
      throw new UpstreamException(HttpStatus.BAD_GATEWAY, null,
          "Unreadable response from IDIM proxy: " + new String(body, StandardCharsets.UTF_8),
          UPSTREAM, e);
    }
  }

  private RestClient clientFor(ApiInstanceEnv env) {
    RestClient client = clients.get(env);
    if (client == null) {
      throw new UpstreamException(HttpStatus.INTERNAL_SERVER_ERROR, null,
          "IDIM proxy " + env + " instance is not configured.", UPSTREAM);
    }
    return client;
  }
}
