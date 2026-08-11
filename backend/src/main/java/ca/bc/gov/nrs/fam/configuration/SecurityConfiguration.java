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
      "/smoke_test/**",
      // Authenticated by the X-API-Key shared secret in the controller, not by a
      // bearer token: it is called by a scheduled job with no signed-in user.
      // The controller fails closed when no key is configured.
      "/users/users-information"
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
        // No cookies or server-side session: every request carries a bearer
        // token, so there is no CSRF surface to protect.
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
    config.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
