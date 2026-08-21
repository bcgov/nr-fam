package ca.bc.gov.nrs.fam.configuration;

import ca.bc.gov.nrs.fam.security.ProductionEnvironmentGuard;
import ca.bc.gov.nrs.fam.security.RequesterArgumentResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfiguration implements WebMvcConfigurer {

  private final RequesterArgumentResolver requesterArgumentResolver;
  private final ProductionEnvironmentGuard productionEnvironmentGuard;

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(requesterArgumentResolver);
  }

  /**
   * Registered against every path so a new endpoint cannot opt out of the
   * production guard by forgetting to call it.
   */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(productionEnvironmentGuard).addPathPatterns("/**");
  }
}
