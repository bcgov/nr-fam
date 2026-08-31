package ca.bc.gov.nrs.fam.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards the SMTP transport settings in {@code application.yml}.
 *
 * <p>{@code spring.mail.properties} is a free-form map handed to Jakarta Mail,
 * so a mistyped or misnested key is not a startup failure - it is a setting that
 * silently does nothing. That is a poor way to hold a security control, hence
 * this test binds the real file and reads back the key Jakarta Mail looks for.
 */
@DisplayName("Mail transport settings in application.yml")
class MailSslConfigurationTest {

  private MailProperties bindMailProperties() throws IOException {
    List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
        .load("application.yml", new ClassPathResource("application.yml"));
    MutablePropertySources sources = new MutablePropertySources();
    loaded.forEach(sources::addLast);

    return new Binder(
            ConfigurationPropertySources.from(sources),
            new PropertySourcesPlaceholdersResolver(sources))
        .bind("spring.mail", MailProperties.class)
        .get();
  }

  @Test
  @DisplayName("verifies the relay's certificate matches the host we dialled")
  void checksServerIdentity() throws IOException {
    assertThat(bindMailProperties().getProperties())
        .containsEntry("mail.smtp.ssl.checkserveridentity", "true");
  }

  @Test
  @DisplayName("does not blanket-trust certificates, which would undo the check")
  void doesNotTrustAllHosts() throws IOException {
    assertThat(bindMailProperties().getProperties()).doesNotContainKey("mail.smtp.ssl.trust");
  }
}
