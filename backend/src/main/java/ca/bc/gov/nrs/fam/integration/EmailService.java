package ca.bc.gov.nrs.fam.integration;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Outbound email, sent through an SMTP relay.
 *
 * <p>Replaces the GC Notify integration. The two differ in where the message is
 * composed: GC Notify held the templates and FAM passed it a personalisation map,
 * so the wording lived with a third party and every change needed a template id
 * kept in step. Here the body is composed in this service, so what is sent is
 * visible in this repository and reviewable with the code that triggers it.
 *
 * <h2>Sending is opt-in</h2>
 *
 * <p>With no {@code spring.mail.host} configured, nothing is sent and each
 * attempt is logged. That is the local default, and it is deliberate: a developer
 * running FAM against real user data should not be able to send mail to real
 * people by accident.
 *
 * <h2>A failed send does not fail the operation</h2>
 *
 * <p>Email is a notification about something that already happened. If a grant
 * succeeded and the relay is down, the grant still succeeded - reporting failure
 * would invite the administrator to retry a grant that does not need retrying.
 * Failures are logged, not thrown.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

  private final JavaMailSender mailSender;
  private final MailProperties mailProperties;
  private final FamProperties famProperties;

  @PostConstruct
  void reportConfiguration() {
    if (!isConfigured()) {
      log.info("No SMTP host configured; FAM will not send email. "
          + "Set spring.mail.host and fam.integration.smtp.from to enable it.");
      return;
    }
    log.info("SMTP configured (host={}, from={})", mailProperties.getHost(), from());
  }

  /**
   * Whether email can be sent at all.
   *
   * <p>Both a relay and an envelope sender are required: a relay will reject a
   * message with no {@code From}, so a host without one is not "partly
   * configured", it is unusable.
   */
  public boolean isConfigured() {
    return notBlank(mailProperties.getHost()) && notBlank(from());
  }

  /**
   * Send a plain-text message.
   *
   * @return true when the message was handed to the relay; false when sending is
   *     disabled or the relay rejected it. Never throws.
   */
  public boolean send(List<String> to, String subject, String body) {
    List<String> recipients = to == null ? List.of()
        : to.stream().filter(EmailService::notBlank).distinct().toList();

    if (recipients.isEmpty()) {
      log.debug("No recipients for '{}'; nothing sent.", subject);
      return false;
    }

    if (!isConfigured()) {
      log.info("SMTP is not configured; not sending '{}' to {} recipient(s).",
          subject, recipients.size());
      return false;
    }

    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from());
    message.setTo(recipients.toArray(new String[0]));
    message.setSubject(subject);
    message.setText(body);

    String replyTo = famProperties.integration().smtp().replyTo();
    if (notBlank(replyTo)) {
      message.setReplyTo(replyTo);
    }

    try {
      mailSender.send(message);
      log.debug("Sent '{}' to {} recipient(s).", subject, recipients.size());
      return true;
    } catch (MailException e) {
      // Deliberately not rethrown - see the class note. The caller's operation
      // has already happened and is not undone by a failed notification.
      log.warn("Could not send '{}' to {} recipient(s): {}",
          subject, recipients.size(), e.getMessage());
      return false;
    }
  }

  private String from() {
    FamProperties.Integration integration = famProperties.integration();
    return integration == null || integration.smtp() == null ? null : integration.smtp().from();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }
}
