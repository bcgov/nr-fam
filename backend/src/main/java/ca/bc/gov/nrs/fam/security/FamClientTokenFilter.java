package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects tokens that were not issued to FAM's own OIDC client.
 *
 * <p>Port of {@code jwt_validation.enforce_fam_client_token}. FAM's internal API
 * is for FAM's own frontend; a downstream application holding a valid realm token
 * must not be able to drive the admin screens with it.
 *
 * <p>The external API is deliberately exempt - that surface exists precisely so
 * other applications' clients can call it, and it is authorised separately by
 * {@code call_api_flag} on the caller's roles.
 */
@Slf4j
@Component
public class FamClientTokenFilter extends OncePerRequestFilter {

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  /** Paths this check does not apply to. */
  private static final String[] EXEMPT_PATHS = {
      "/external/**",
      "/actuator/**",
      "/v3/api-docs/**",
      "/docs/**",
      "/swagger-ui/**",
      "/smoke_test/**"
  };

  private final String famClientId;
  private final TokenClaimsReader claimsReader;
  private final ObjectMapper objectMapper;

  public FamClientTokenFilter(
      @Value("${fam.oidc.fam-client-id:}") String famClientId,
      TokenClaimsReader claimsReader,
      ObjectMapper objectMapper) {
    this.famClientId = famClientId;
    this.claimsReader = claimsReader;
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // With no FAM client id configured the check cannot be made. Local development
    // runs this way; deployed environments set it.
    if (famClientId == null || famClientId.isBlank()) {
      return true;
    }
    String path = request.getServletPath();
    for (String exempt : EXEMPT_PATHS) {
      if (PATH_MATCHER.match(exempt, path)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
      String tokenClientId = claimsReader.appClientId(jwt);
      if (!famClientId.equals(tokenClientId)) {
        log.warn("Rejecting token issued to client '{}' on internal path {}",
            tokenClientId, request.getServletPath());
        writeUnauthorized(response);
        return;
      }
    }

    filterChain.doFilter(request, response);
  }

  /**
   * Written directly rather than raised: the exception handler does not run for
   * filter-thrown errors, and the response shape must still match the rest of the
   * API.
   */
  private void writeUnauthorized(HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    objectMapper.writeValue(response.getOutputStream(), Map.of("detail", Map.of(
        "code", ErrorCode.INVALID_OIDC_CLIENT,
        "description", "Incorrect client ID.")));
  }
}
