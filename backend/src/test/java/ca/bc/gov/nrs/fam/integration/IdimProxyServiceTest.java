package ca.bc.gov.nrs.fam.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import ca.bc.gov.nrs.fam.security.Requester;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@DisplayName("IdimProxyService (port of idim_proxy.py)")
class IdimProxyServiceTest {

  private static final String ORG_A = "000000000000000000000000000000AA";
  private static final String ORG_B = "000000000000000000000000000000BB";

  private MockWebServer server;
  private IdimProxyService service;

  private static MockResponse json(int code, String body) {
    return new MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }

  private static Requester requester(UserType type, String businessGuid) {
    return Requester.builder()
        .userId(1L)
        .userName("TESTER")
        .userType(type)
        .userGuid("A".repeat(32))
        .businessGuid(businessGuid)
        .build();
  }

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();

    String baseUrl = server.url("/").toString();
    // Trim the trailing slash so the "/api/idim-webservice" suffix does not double up.
    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

    FamProperties.Integration.IdimProxy config = new FamProperties.Integration.IdimProxy(
        new FamProperties.Integration.IdimProxy.Instance(baseUrl),
        new FamProperties.Integration.IdimProxy.Instance(baseUrl),
        "idim-key",
        new FamProperties.Integration.Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(2)));

    ObjectMapper objectMapper = new ObjectMapper();
    service = new IdimProxyService(
        new FamProperties("dev", null, new FamProperties.Integration(null, config, null), null),
        new RestClientFactory(), new UpstreamErrorTranslator(objectMapper), objectMapper);
    service.initClients();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Nested
  @DisplayName("lookupIdir")
  class LookupIdir {

    @Test
    @DisplayName("sends userId and the requester's GUID, and parses camelCase fields")
    void looksUpIdirUser() throws Exception {
      server.enqueue(json(200, """
          {"found":true,"userId":"JSMITH","guid":"B0000000000000000000000000000001",
           "firstName":"Jane","lastName":"Smith","email":"jane@gov.bc.ca"}"""));

      IdimProxyIdirInfoDto result = service.lookupIdir(
          "JSMITH", requester(UserType.IDIR, null), ApiInstanceEnv.TEST);

      assertThat(result.found()).isTrue();
      assertThat(result.userId()).isEqualTo("JSMITH");
      assertThat(result.firstName()).isEqualTo("Jane");
      assertThat(result.email()).isEqualTo("jane@gov.bc.ca");

      RecordedRequest request = server.takeRequest();
      assertThat(request.getPath())
          .startsWith("/api/idim-webservice/idir-account-detail")
          .contains("userId=JSMITH")
          // IDIM audits lookups against the person, not against FAM.
          .contains("requesterUserGuid=" + "A".repeat(32));
      assertThat(request.getHeader("X-API-KEY")).isEqualTo("idim-key");
    }
  }

  @Nested
  @DisplayName("lookupBusinessBceid")
  class LookupBusinessBceid {

    @Test
    @DisplayName("maps an IDIR requester to the Internal account type")
    void mapsIdirAccountType() throws Exception {
      server.enqueue(json(200, "{\"found\":false,\"userId\":\"NOBODY\"}"));

      service.lookupBusinessBceid(IdimSearchUserParamType.USER_ID, "NOBODY",
          requester(UserType.IDIR, null), ApiInstanceEnv.TEST);

      assertThat(server.takeRequest().getPath())
          .contains("requesterAccountTypeCode=Internal")
          .contains("searchUserBy=userId")
          .contains("searchValue=NOBODY");
    }

    @Test
    @DisplayName("maps a BCeID requester to the Business account type")
    void mapsBceidAccountType() throws Exception {
      server.enqueue(json(200, "{\"found\":false,\"userId\":\"NOBODY\"}"));

      service.lookupBusinessBceid(IdimSearchUserParamType.USER_GUID, "GUID",
          requester(UserType.BCEID, ORG_A), ApiInstanceEnv.TEST);

      assertThat(server.takeRequest().getPath()).contains("requesterAccountTypeCode=Business");
    }

    @Test
    @DisplayName("allows a BCeID requester to see a user in their own organisation")
    void allowsSameOrganisation() {
      server.enqueue(json(200, """
          {"found":true,"userId":"COLLEAGUE","businessGuid":"%s"}""".formatted(ORG_A)));

      assertThatCode(() -> service.lookupBusinessBceid(
          IdimSearchUserParamType.USER_ID, "COLLEAGUE",
          requester(UserType.BCEID, ORG_A), ApiInstanceEnv.TEST))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("blocks a BCeID requester from a user in another organisation")
    void blocksDifferentOrganisation() {
      server.enqueue(json(200, """
          {"found":true,"userId":"OUTSIDER","businessGuid":"%s"}""".formatted(ORG_B)));

      assertThatThrownBy(() -> service.lookupBusinessBceid(
          IdimSearchUserParamType.USER_ID, "OUTSIDER",
          requester(UserType.BCEID, ORG_A), ApiInstanceEnv.TEST))
          .isInstanceOf(FamHttpException.class)
          .extracting("code", "status")
          .containsExactly(ErrorCode.PERMISSION_REQUIRED, HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("does not apply the organisation rule to an IDIR requester")
    void idirRequesterCrossesOrganisations() {
      server.enqueue(json(200, """
          {"found":true,"userId":"ANYONE","businessGuid":"%s"}""".formatted(ORG_B)));

      IdimProxyBceidInfoDto result = service.lookupBusinessBceid(
          IdimSearchUserParamType.USER_ID, "ANYONE",
          requester(UserType.IDIR, null), ApiInstanceEnv.TEST);

      assertThat(result.businessGuid()).isEqualTo(ORG_B);
    }

    @Test
    @DisplayName("does not apply the organisation rule when the user was not found")
    void notFoundSkipsOrganisationCheck() {
      server.enqueue(json(200, "{\"found\":false,\"userId\":\"GHOST\"}"));

      assertThatCode(() -> service.lookupBusinessBceid(
          IdimSearchUserParamType.USER_ID, "GHOST",
          requester(UserType.BCEID, ORG_A), ApiInstanceEnv.TEST))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a BCSC requester, which has no IDIM account type")
    void rejectsUnmappableRequesterType() {
      assertThatThrownBy(() -> service.lookupBusinessBceid(
          IdimSearchUserParamType.USER_ID, "ANYONE",
          requester(UserType.BCSC_PROD, null), ApiInstanceEnv.TEST))
          .isInstanceOf(FamHttpException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.MISSING_KEY_ATTRIBUTE);
    }
  }

  @Nested
  @DisplayName("searchIdirUsers")
  class SearchIdirUsers {

    @Test
    @DisplayName("POSTs with the requester GUID in the body and search terms in the query")
    void postsSearch() throws Exception {
      server.enqueue(json(200, """
          {"totalItems":1,"pageSize":50,
           "items":[{"userId":"JSMITH","guid":"G","firstName":"Jane","lastName":"Smith",
                     "email":"jane@gov.bc.ca"}]}"""));

      IdimIdirUsersSearchParams params = new IdimIdirUsersSearchParams();
      params.setLastName("Smi");

      IdimProxyIdirUsersSearchResultDto result =
          service.searchIdirUsers(params, requester(UserType.IDIR, null), ApiInstanceEnv.TEST);

      assertThat(result.totalItems()).isEqualTo(1);
      assertThat(result.items()).singleElement()
          .extracting(i -> i.userId()).isEqualTo("JSMITH");

      RecordedRequest request = server.takeRequest();
      assertThat(request.getMethod()).isEqualTo("POST");
      assertThat(request.getPath())
          .startsWith("/api/idim-webservice/idir-users/search")
          .contains("lastName=Smi")
          // Names match partially, so the caller can type a fragment.
          .contains("lastNameMatchMode=Contains");
      assertThat(request.getBody().readUtf8()).contains("requesterUserGuid");
    }

    @Test
    @DisplayName("matches a user id exactly, since a partial id returns noise")
    void userIdUsesExactMatch() throws Exception {
      server.enqueue(json(200, "{\"totalItems\":0,\"pageSize\":50,\"items\":[]}"));

      IdimIdirUsersSearchParams params = new IdimIdirUsersSearchParams();
      params.setUserId("JSMITH");

      service.searchIdirUsers(params, requester(UserType.IDIR, null), ApiInstanceEnv.TEST);

      assertThat(server.takeRequest().getPath()).contains("userIdMatchMode=Exact");
    }
  }
}
