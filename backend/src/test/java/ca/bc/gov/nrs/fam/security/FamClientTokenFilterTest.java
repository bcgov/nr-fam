package ca.bc.gov.nrs.fam.security;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Enforces that FAM's internal API only accepts tokens issued to FAM's own client.
 *
 * <p>Without this a downstream application holding a valid realm token could drive
 * the admin screens, so the exempt-path list is as much a part of the contract as
 * the check itself.
 */
@DisplayName("FamClientTokenFilter (port of enforce_fam_client_token)")
class FamClientTokenFilterTest {

  private static final String FAM_CLIENT = "fam-console";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TokenClaimsReader claimsReader = new TokenClaimsReader();

  private FamClientTokenFilter filter(String configuredClientId) {
    return new FamClientTokenFilter(configuredClientId, claimsReader, objectMapper);
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private static void authenticateWithClient(String azp) {
    Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
        Map.of("alg", "RS256"), Map.of("sub", "user", "azp", azp));
    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken(jwt, null, "ROLE_USER"));
  }

  private static MockHttpServletRequest request(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);
    return request;
  }

  private MockHttpServletResponse runFilter(
      FamClientTokenFilter filter, String path) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = new MockFilterChain();
    filter.doFilter(request(path), response, chain);
    return response;
  }

  @Test
  @DisplayName("allows a token issued to FAM's own client")
  void allowsFamClient() throws Exception {
    authenticateWithClient(FAM_CLIENT);

    assertThat(runFilter(filter(FAM_CLIENT), "/fam-applications/1/user-role-assignment")
        .getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("rejects a token issued to another application's client")
  void rejectsOtherClient() throws Exception {
    authenticateWithClient("fom-client");

    MockHttpServletResponse response =
        runFilter(filter(FAM_CLIENT), "/fam-applications/1/user-role-assignment");

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    // Same error shape as the rest of the API - the exception handler does not
    // run for filter-thrown errors, so the filter writes it itself.
    assertThat(response.getContentAsString())
        .contains(ErrorCode.INVALID_OIDC_CLIENT)
        .contains("Incorrect client ID.");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "/external/v1/users",
      "/actuator/health",
      "/v3/api-docs",
      "/docs/index.html",
      "/smoke_test"
  })
  @DisplayName("exempts the external API and the unauthenticated surfaces")
  void exemptsExternalAndPublicPaths(String path) throws Exception {
    // /external exists precisely so other applications' clients can call it; it is
    // authorised separately by call_api_flag.
    authenticateWithClient("fom-client");

    assertThat(runFilter(filter(FAM_CLIENT), path).getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("skips the check entirely when no FAM client id is configured")
  void skipsWhenUnconfigured() throws Exception {
    // Local development runs without it; deployed environments set it.
    authenticateWithClient("anything");

    assertThat(runFilter(filter(""), "/fam-applications/1").getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("leaves unauthenticated requests to the security chain")
  void ignoresUnauthenticatedRequests() throws Exception {
    // No authentication in context: rejecting here would mask the 401 the
    // resource server already produces.
    assertThat(runFilter(filter(FAM_CLIENT), "/fam-applications/1").getStatus())
        .isEqualTo(200);
  }
}
