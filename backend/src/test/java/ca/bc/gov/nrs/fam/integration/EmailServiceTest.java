package ca.bc.gov.nrs.fam.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmailService (SMTP, replacing GC Notify)")
class EmailServiceTest {

  @Mock private JavaMailSender mailSender;

  private EmailService serviceWith(String host, String from, String replyTo) {
    MailProperties mailProperties = new MailProperties();
    mailProperties.setHost(host);

    FamProperties famProperties = new FamProperties("dev", null,
        new FamProperties.Integration(null, null, null,
            new FamProperties.Integration.Smtp(from, replyTo)));

    return new EmailService(mailSender, mailProperties, famProperties);
  }

  private EmailService configured() {
    return serviceWith("smtp.example.gov.bc.ca", "fam@gov.bc.ca", null);
  }

  private SimpleMailMessage captureSent() {
    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender).send(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("sends a message when a relay and sender are configured")
  void sendsWhenConfigured() {
    assertThat(configured().send(List.of("a@gov.bc.ca"), "Access granted", "body")).isTrue();

    SimpleMailMessage sent = captureSent();
    assertThat(sent.getFrom()).isEqualTo("fam@gov.bc.ca");
    assertThat(sent.getTo()).containsExactly("a@gov.bc.ca");
    assertThat(sent.getSubject()).isEqualTo("Access granted");
  }

  @Test
  @DisplayName("sends nothing when no relay is configured")
  void doesNotSendWithoutAHost() {
    // The local default. Running FAM against real user data must not be able to
    // mail real people by accident.
    EmailService service = serviceWith("", "fam@gov.bc.ca", null);

    assertThat(service.isConfigured()).isFalse();
    assertThat(service.send(List.of("a@gov.bc.ca"), "Subject", "body")).isFalse();
    verify(mailSender, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  @DisplayName("sends nothing when no envelope sender is configured")
  void doesNotSendWithoutAFrom() {
    // A relay rejects a message with no From, so a host alone is unusable rather
    // than partly usable.
    EmailService service = serviceWith("smtp.example.gov.bc.ca", "", null);

    assertThat(service.isConfigured()).isFalse();
    verify(mailSender, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  @DisplayName("a relay failure does not propagate to the caller")
  void relayFailureIsSwallowed() {
    // Email reports something that already happened. A grant that succeeded is
    // not undone by a failed notification, and surfacing an error would invite a
    // retry of work that does not need retrying.
    doThrow(new MailSendException("relay down"))
        .when(mailSender).send(any(SimpleMailMessage.class));

    assertThat(configured().send(List.of("a@gov.bc.ca"), "Subject", "body")).isFalse();
  }

  @Test
  @DisplayName("drops blank recipients and de-duplicates the rest")
  void cleansRecipients() {
    assertThat(configured().send(
        java.util.Arrays.asList("a@gov.bc.ca", "", null, "a@gov.bc.ca", "b@gov.bc.ca"),
        "Subject", "body")).isTrue();

    assertThat(captureSent().getTo()).containsExactly("a@gov.bc.ca", "b@gov.bc.ca");
  }

  @Test
  @DisplayName("sends nothing when every recipient is blank")
  void noRecipientsSendsNothing() {
    assertThat(configured().send(java.util.Arrays.asList("", null), "Subject", "body")).isFalse();
    verify(mailSender, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  @DisplayName("sets Reply-To only when one is configured")
  void setsReplyToWhenConfigured() {
    assertThat(serviceWith("smtp.example.gov.bc.ca", "fam@gov.bc.ca", "team@gov.bc.ca")
        .send(List.of("a@gov.bc.ca"), "Subject", "body")).isTrue();
    assertThat(captureSent().getReplyTo()).isEqualTo("team@gov.bc.ca");
  }

  @Test
  @DisplayName("leaves Reply-To unset when none is configured")
  void leavesReplyToUnset() {
    configured().send(List.of("a@gov.bc.ca"), "Subject", "body");
    assertThat(captureSent().getReplyTo()).isNull();
  }
}
