package ca.bc.gov.nrs.fam.integration;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.dto.UserLookupBceidUserDto;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirSearchResult;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Client for <b>nr-user-lookup-api</b>, the shared BC Gov identity directory.
 *
 * <p>Ported from {@code nr-fsp-new}'s {@code UserLookupClient}. Replaces the IDIM
 * proxy, which FAM previously called for the same three lookups.
 *
 * <h2>Two consequences of the swap</h2>
 *
 * <p><b>Lookups are no longer attributed to the person making them.</b> IDIM
 * required {@code requesterUserGuid} and {@code requesterAccountTypeCode} on
 * every call and audited against them. This service authenticates as FAM's own
 * service account, so the directory sees FAM rather than the administrator. If
 * per-user attribution is required, it has to be recorded on FAM's side.
 *
 * <p><b>The same-organisation rule is not upstream's job any more.</b> IDIM
 * received the requester and could reason about them; this API cannot. The rule -
 * a Business BCeID administrator may not read a user outside their own
 * organisation - is enforced by the caller instead. See
 * {@code IdentityLookupController}.
 *
 * <h2>Failure handling</h2>
 *
 * <p>Unlike FSP, an upstream failure is <em>not</em> swallowed into an empty
 * result. FSP's callers are best-effort enrichment; FAM's are an administrator
 * searching for a person to grant access to, and "no results" is a materially
 * different answer from "the directory is unreachable" - the first invites them
 * to give up, the second to retry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserLookupClient {

  private static final String UPSTREAM = "user-lookup-api";

  private static final String BASE_PATH = "/api/v1/user-lookup";
  private static final String IDIR_DETAIL_PATH = BASE_PATH + "/idir-account-detail";
  private static final String IDIR_SEARCH_PATH = BASE_PATH + "/idir-users/search";
  private static final String BUSINESS_BCEID_PATH = BASE_PATH + "/businessBceid";

  private final FamProperties famProperties;
  private final RestClientFactory restClientFactory;
  private final UpstreamErrorTranslator errorTranslator;

  private RestClient http;
  private ClientCredentialsTokenSource tokenSource;

  @PostConstruct
  void init() {
    FamProperties.Integration.UserLookup config = config();
    if (config == null || config.baseUrl() == null || config.baseUrl().isBlank()) {
      log.info("nr-user-lookup-api is not configured; identity lookups will fail at call time.");
      return;
    }

    http = restClientFactory.create(config.baseUrl(),
        config.timeouts().connect(), config.timeouts().read(),
        headers -> headers.setAccept(List.of(MediaType.APPLICATION_JSON)));

    tokenSource = ClientCredentialsTokenSource.fromProperties(
        config.tokenUrl(), config.clientId(), config.clientSecret(), config.scope(),
        config.timeouts().connect(), config.timeouts().read(), restClientFactory);

    if (tokenSource == null) {
      log.warn("nr-user-lookup-api configured without credentials; calls will be "
          + "unauthenticated. Set USER_LOOKUP_TOKEN_URL / CLIENT_ID / CLIENT_SECRET.");
    } else {
      log.info("nr-user-lookup-api client active (base-url={}, client-id={})",
          config.baseUrl(), tokenSource.clientId());
    }
  }

  private FamProperties.Integration.UserLookup config() {
    return famProperties.integration() == null ? null : famProperties.integration().userLookup();
  }

  public boolean isConfigured() {
    FamProperties.Integration.UserLookup config = config();
    return config != null && config.baseUrl() != null && !config.baseUrl().isBlank();
  }

  /**
   * Partial-match IDIR search. Only non-blank criteria are sent.
   *
   * <p>An empty result means nobody matched; an unreachable directory throws.
   *
   * <p>The criteria are sent as flat query parameters. The directory's published
   * spec renders them as a single object parameter named {@code query}, which is
   * how springdoc describes a bean bound from the query string when it is not
   * annotated {@code @ParameterObject} - Spring still binds the individual
   * fields. This matches what nr-fsp-new sends.
   *
   * <p>Not sent: {@code firstNameMatchMode}, {@code lastNameMatchMode} and
   * {@code userIdMatchMode}, which the directory also accepts. Their defaults
   * decide whether this search is partial or exact, and neither FAM nor FSP sets
   * them.
   */
  public UserLookupIdirSearchResult searchIdir(
      String userId, String firstName, String lastName, Integer pageSize) {

    String user = normalize(userId);
    String first = normalize(firstName);
    String last = normalize(lastName);

    UserLookupIdirSearchResult result = exchange(() -> http.post()
        .uri(builder -> {
          builder.path(IDIR_SEARCH_PATH);
          if (user != null) {
            builder.queryParam("userId", user);
          }
          if (first != null) {
            builder.queryParam("firstName", first);
          }
          if (last != null) {
            builder.queryParam("lastName", last);
          }
          // The caller's page size is forwarded rather than dropped: the picker
          // asks for a wide result set, and the directory's own default is much
          // smaller.
          if (pageSize != null && pageSize > 0) {
            builder.queryParam("pageSize", pageSize);
          }
          return builder.build();
        })
        .headers(this::applyAuth)
        .retrieve()
        .body(UserLookupIdirSearchResult.class));

    return result == null ? UserLookupIdirSearchResult.empty() : result;
  }

  /** Exact IDIR match by user id. Empty when the directory reports no match. */
  public Optional<UserLookupIdirUserDto> getIdirDetail(String userId) {
    String user = normalize(userId);
    if (user == null) {
      return Optional.empty();
    }

    UserLookupIdirUserDto result = exchange(() -> http.get()
        .uri(builder -> builder.path(IDIR_DETAIL_PATH).queryParam("userId", user).build())
        .headers(this::applyAuth)
        .retrieve()
        .body(UserLookupIdirUserDto.class));

    return result == null || !result.found() ? Optional.empty() : Optional.of(result);
  }

  /**
   * Business BCeID lookup, by user id or user GUID.
   *
   * <p>Returns whatever the directory holds. It does <em>not</em> apply the
   * same-organisation rule: this client has no requester to compare against, so
   * that check belongs to the caller.
   */
  public Optional<UserLookupBceidUserDto> getBusinessBceid(SearchBy searchBy, String searchValue) {
    String value = normalize(searchValue);
    if (searchBy == null || value == null) {
      return Optional.empty();
    }

    UserLookupBceidUserDto result = exchange(() -> http.get()
        .uri(builder -> builder.path(BUSINESS_BCEID_PATH)
            .queryParam("searchUserBy", searchBy.wireValue())
            .queryParam("searchValue", value)
            .build())
        .headers(this::applyAuth)
        .retrieve()
        .body(UserLookupBceidUserDto.class));

    return result == null || !result.found() ? Optional.empty() : Optional.of(result);
  }

  /** How {@code searchValue} should be interpreted on a BCeID lookup. */
  public enum SearchBy {
    USER_ID("userId"),
    USER_GUID("userGuid");

    private final String wireValue;

    SearchBy(String wireValue) {
      this.wireValue = wireValue;
    }

    public String wireValue() {
      return wireValue;
    }
  }

  // ---------------------------------------------------------------------------

  private void applyAuth(HttpHeaders headers) {
    if (tokenSource == null) {
      return;
    }
    String token = tokenSource.fetchCached();
    if (!token.isBlank()) {
      headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
  }

  private <T> T exchange(java.util.function.Supplier<T> call) {
    if (!isConfigured()) {
      throw new ca.bc.gov.nrs.fam.exception.UpstreamException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          ca.bc.gov.nrs.fam.constants.ErrorCode.INVALID_OPERATION,
          "nr-user-lookup-api is not configured (USER_LOOKUP_BASE_URL).", UPSTREAM);
    }
    try {
      return call.get();
    } catch (ResourceAccessException e) {
      throw errorTranslator.connectivityFailure(UPSTREAM, e);
    }
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
