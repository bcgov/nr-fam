package ca.bc.gov.nrs.fam.configuration;

import ca.bc.gov.nrs.fam.security.FamClientTokenFilter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Resource-server security against BC Gov SSO (Keycloak).
 *
 * <p>Upstream validated AWS Cognito access tokens by hand in
 * {@code jwt_validation.py} (fetching JWKS at startup via {@code init_jwks} and
 * checking claims per request). Spring Security's resource server does the
 * equivalent, with JWKS caching and rotation handled for us.
 *
 * <p>Authorisation beyond "is authenticated" - the app-admin, delegated-admin and
 * role checks that {@code router_guards.py} performed - is applied per endpoint
 * with method security, not here.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

  /** Endpoints reachable without a bearer token. */
  private static final String[] PUBLIC_PATHS = {
      "/actuator/health/**",
      "/actuator/info",
      "/actuator/prometheus",
      "/v3/api-docs/**",
      "/docs/**",
      "/swagger-ui/**",
      "/smoke_test/**"
  };

  private final FamProperties famProperties;
  private final FamClientTokenFilter famClientTokenFilter;

  public SecurityConfiguration(
      FamProperties famProperties, FamClientTokenFilter famClientTokenFilter) {
    this.famProperties = famProperties;
    this.famClientTokenFilter = famClientTokenFilter;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .cors(Customizer.withDefaults())
        // Deliberately disabled, and reported by CodeQL as
        // java/spring-disabled-csrf-protection. CSRF defends a browser
        // credential the browser attaches by itself; this service has none.
        // It issues no cookie and creates no session (see the STATELESS policy
        // below), and the only credential it accepts is a bearer token the SPA
        // reads out of the OIDC client's storage and sets on each request. A
        // cross-site form post carries no such header, so it arrives
        // unauthenticated. Enabling CSRF here would hand tokens to a client
        // that has nowhere to keep them and no cookie to pair them with.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
            .requestMatchers(PUBLIC_PATHS).permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        // Runs after the bearer token has been validated and the security context
        // populated, so it can inspect the authenticated token's client id.
        .addFilterAfter(famClientTokenFilter, BearerTokenAuthenticationFilter.class)
        .build();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(famProperties.cors().allowedOrigins());
    config.setAllowedMethods(List.of("*"));
    config.setAllowedHeaders(List.of("*"));
    // Left off on purpose: the SPA authenticates with an Authorization header,
    // never a cookie, so nothing needs to ride along credentialed. Allowing
    // credentials would let an allowed origin make cookie-bearing calls - the
    // one thing that would give this API a CSRF surface.
    config.setAllowCredentials(false);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
