package ca.bc.gov.nrs.fam.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirSearchResult;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UserLookupClient (nr-user-lookup-api)")
class UserLookupClientTest {

  private MockWebServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  private UserLookupClient clientWith(String tokenUrl, String clientId, String clientSecret) {
    FamProperties.Integration.UserLookup config = new FamProperties.Integration.UserLookup(
        server.url("/").toString().replaceAll("/$", ""),
        tokenUrl, clientId, clientSecret, "",
        new FamProperties.Integration.Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(2)));

    FamProperties properties = new FamProperties("dev", null,
        new FamProperties.Integration(null, null, config, null), null);

    // The app's mapper: snake_case is global, and the directory speaks camelCase.
    ObjectMapper objectMapper = new ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    UserLookupClient created = new UserLookupClient(
        properties, new RestClientFactory(), new UpstreamErrorTranslator(objectMapper));
    created.init();
    return created;
  }

  private UserLookupClient unauthenticatedClient() {
    return clientWith("", "", "");
  }

  private void enqueueJson(String body) {
    server.enqueue(new MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(body));
  }

  @Test
  @DisplayName("reads camelCase fields the directory returns")
  void readsCamelCaseFields() {
    // FAM's own API is snake_case and that strategy is global, so a camelCase
    // upstream needs its DTOs pinned or every multi-word field is null.
    enqueueJson("""
        {"found":true,"userId":"JSMITH","guid":"AABB",
         "firstName":"Jane","lastName":"Smith","email":"jane@gov.bc.ca"}""");

    assertThat(unauthenticatedClient().getIdirDetail("JSMITH")).hasValueSatisfying(user -> {
      assertThat(user.firstName()).isEqualTo("Jane");
      assertThat(user.lastName()).isEqualTo("Smith");
      assertThat(user.email()).isEqualTo("jane@gov.bc.ca");
    });
  }

  @Test
  @DisplayName("treats found=false as no match rather than a result")
  void notFoundIsEmpty() {
    enqueueJson("{\"found\":false}");
    assertThat(unauthenticatedClient().getIdirDetail("GHOST")).isEmpty();
  }

  @Test
  @DisplayName("sends only the search criteria that were supplied")
  void sendsOnlySuppliedCriteria() throws Exception {
    enqueueJson("{\"totalItems\":0,\"pageSize\":0,\"items\":[]}");

    unauthenticatedClient().searchIdir(null, "Jane", "  ", null);

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getPath()).contains("/api/v1/user-lookup/idir-users/search");
    assertThat(request.getPath()).contains("firstName=Jane");
    // Blank and null criteria are omitted, not sent as empty parameters.
    assertThat(request.getPath()).doesNotContain("userId=").doesNotContain("lastName=");
  }

  @Test
  @DisplayName("forwards the caller's page size instead of dropping it")
  void forwardsPageSize() throws Exception {
    // The picker asks for a wide result set; the directory's own default is much
    // smaller, so dropping this silently truncates the search.
    enqueueJson("{\"totalItems\":0,\"pageSize\":0,\"items\":[]}");

    unauthenticatedClient().searchIdir("JSMITH", null, null, 500);

    assertThat(server.takeRequest().getPath()).contains("pageSize=500");
  }

  @Test
  @DisplayName("reads a search page and never returns a null item list")
  void readsSearchPage() {
    enqueueJson("""
        {"totalItems":1,"pageSize":50,
         "items":[{"userId":"JSMITH","firstName":"Jane","lastName":"Smith"}]}""");

    UserLookupIdirSearchResult result = unauthenticatedClient().searchIdir("JSMITH", null, null, null);

    assertThat(result.totalItems()).isEqualTo(1);
    assertThat(result.items()).singleElement()
        .satisfies(u -> assertThat(u.firstName()).isEqualTo("Jane"));
  }

  @Test
  @DisplayName("a response with no items reads as empty, not as a null list")
  void missingItemsIsEmptyList() {
    enqueueJson("{\"totalItems\":0,\"pageSize\":50}");
    assertThat(unauthenticatedClient().searchIdir("X", null, null, null).items()).isEmpty();
  }

  @Test
  @DisplayName("selects the BCeID search field on the wire")
  void selectsBceidSearchField() throws Exception {
    enqueueJson("{\"found\":true,\"userId\":\"BUSER\",\"businessGuid\":\"ORG\"}");

    unauthenticatedClient()
        .getBusinessBceid(UserLookupClient.SearchBy.USER_GUID, "AABBCC");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getPath()).contains("searchUserBy=userGuid").contains("searchValue=AABBCC");
  }

  @Test
  @DisplayName("returns the organisation without enforcing anything with it")
  void returnsOrganisationWithoutEnforcing() {
    // The same-organisation rule needs a requester to compare against, which this
    // client does not have. It belongs to the caller.
    enqueueJson("{\"found\":true,\"userId\":\"BUSER\",\"businessGuid\":\"OTHER-ORG\"}");

    assertThat(unauthenticatedClient()
        .getBusinessBceid(UserLookupClient.SearchBy.USER_ID, "BUSER"))
        .hasValueSatisfying(u -> assertThat(u.businessGuid()).isEqualTo("OTHER-ORG"));
  }

  @Test
  @DisplayName("an unreachable directory fails loudly rather than reading as no results")
  void unreachableDirectoryThrows() throws Exception {
    // FSP swallows this into an empty list because its callers are best-effort
    // enrichment. FAM's caller is an administrator searching for someone to grant
    // access to, and "nobody matched" is a different answer from "it is down".
    server.shutdown();

    assertThatThrownBy(() -> unauthenticatedClient().searchIdir("JSMITH", null, null, null))
        .isInstanceOf(UpstreamException.class);
  }

  @Test
  @DisplayName("fails clearly when no base url is configured")
  void unconfiguredFailsClearly() {
    FamProperties properties = new FamProperties("dev", null,
        new FamProperties.Integration(null, null,
            new FamProperties.Integration.UserLookup(null, null, null, null, null,
                new FamProperties.Integration.Timeouts(
                    Duration.ofSeconds(1), Duration.ofSeconds(1))), null), null);

    UserLookupClient client = new UserLookupClient(properties, new RestClientFactory(),
        new UpstreamErrorTranslator(new ObjectMapper()));
    client.init();

    assertThat(client.isConfigured()).isFalse();
    assertThatThrownBy(() -> client.getIdirDetail("JSMITH"))
        .isInstanceOf(UpstreamException.class)
        .hasMessageContaining("USER_LOOKUP_BASE_URL");
  }

  @Test
  @DisplayName("rejects a half-configured credential set at startup")
  void rejectsHalfConfiguredCredentials() {
    // A token url with no secret would otherwise call out unauthenticated, and
    // the directory's rejection would surface as "no results".
    assertThatThrownBy(() -> clientWith("https://example.invalid/token", "an-id", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("client-secret");
  }

  @Test
  @DisplayName("blank criteria yield no call at all")
  void blankLookupSkipsTheCall() {
    assertThat(unauthenticatedClient().getIdirDetail("   ")).isEmpty();
    assertThat(server.getRequestCount()).isZero();
  }
}
