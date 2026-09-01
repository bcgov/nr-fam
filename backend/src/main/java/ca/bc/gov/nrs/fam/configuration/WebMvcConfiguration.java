package ca.bc.gov.nrs.fam.configuration;

import ca.bc.gov.nrs.fam.constants.UserTypeConverter;
import ca.bc.gov.nrs.fam.security.RequesterArgumentResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfiguration implements WebMvcConfigurer {

  private final RequesterArgumentResolver requesterArgumentResolver;
  private final UserTypeConverter userTypeConverter;

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(requesterArgumentResolver);
  }

  /**
   * Registered here rather than left to component scanning to find.
   *
   * <p>Spring Boot does pick up a {@code Converter} bean on its own, which is
   * how this would work without a line here - and is also how it would silently
   * stop working, since nothing at the call site says a query parameter depends
   * on it. The MVC contract is worth reading in one place.
   */
  @Override
  public void addFormatters(FormatterRegistry registry) {
    registry.addConverter(userTypeConverter);
  }
}
