package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.EmailSendingStatus;
import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentResult;
import ca.bc.gov.nrs.fam.integration.EmailService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Tells a user they have been granted access.
 *
 * <p>Restores the notification upstream sent from {@code crud_user_role.py} and
 * {@code access_control_privilege_service.py}, which went when the FAM grant path
 * moved to CSS. The trigger is unchanged: after a grant, to the person who was
 * granted, and only when at least one role actually succeeded.
 *
 * <p>Upstream had two variants - one for an end-user grant, one for appointing a
 * delegated administrator - because those were separate code paths. Under CSS
 * both are a role assignment, so there is one message; what differs is the role
 * named in it.
 *
 * <p>The body is composed here rather than in a template held by a third party,
 * which is the substantive change from GC Notify: what a user receives is
 * reviewable alongside the code that sends it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessGrantedEmailService {

  private final EmailService emailService;

  /**
   * Notify the target of a grant, and report what happened.
   *
   * <p>Never throws. A grant that succeeded is not undone by a notification that
   * did not, so the outcome is recorded on each result instead - the same shape
   * upstream used, so an administrator can see "granted, but not emailed".
   *
   * @param targetUserEmail supplied by the caller - see
   *     {@code CssUserRoleAssignmentRequest.targetUserEmail} for why
   * @param roleNames what each role code is called, from its CSS sidecars -
   *     the short name the screens show. A role with no sidecar is absent and is
   *     named by its code.
   * @return the results, each carrying its email outcome
   */
  public List<CssUserRoleAssignmentResult> notifyGranted(
      String targetUserEmail,
      String applicationName,
      Map<String, String> roleNames,
      List<CssUserRoleAssignmentResult> results) {

    List<CssUserRoleAssignmentResult> assigned =
        results.stream().filter(CssUserRoleAssignmentResult::assigned).toList();

    if (assigned.isEmpty()) {
      // Nothing was granted, so there is nothing to announce.
      return results;
    }

    if (!emailService.isConfigured()) {
      return withStatus(results, EmailSendingStatus.NOT_REQUIRED);
    }

    if (targetUserEmail == null || targetUserEmail.isBlank()) {
      // No address to send to. Not a failure of the grant, and not something a
      // retry would fix.
      log.info("No email address on the grant request; no notification sent.");
      return withStatus(results, EmailSendingStatus.NOT_REQUIRED);
    }

    EmailSendingStatus status;
    try {
      boolean sent = emailService.send(
          List.of(targetUserEmail), subject(applicationName),
          body(applicationName, roleNames, assigned));
      status = sent
          ? EmailSendingStatus.SENT_TO_EMAIL_SERVICE_SUCCESS
          : EmailSendingStatus.SENT_TO_EMAIL_SERVICE_FAILURE;
    } catch (Exception e) {
      // The grant already happened; the notification is not worth undoing it.
      log.warn("Could not notify the granted user: {}", e.getMessage());
      status = EmailSendingStatus.SENT_TO_EMAIL_SERVICE_FAILURE;
    }

    return withStatus(results, status);
  }

  private static String subject(String applicationName) {
    return "You have been granted access to " + applicationName;
  }

  private static String body(
      String applicationName,
      Map<String, String> roleNames,
      List<CssUserRoleAssignmentResult> assigned) {

    StringBuilder body = new StringBuilder();
    body.append("You have been granted access to ").append(applicationName).append(".\n\n");

    body.append(assigned.size() == 1 ? "Role granted:\n\n" : "Roles granted:\n\n");
    for (CssUserRoleAssignmentResult result : assigned) {
      CssRoleNaming.ScopedRoleName parsed = CssRoleNaming.parse(result.roleName());
      /*
        What the role is called, not what it is keyed by. FREP_EDITOR means
        nothing to the person being told they now hold it, and the code is FAM's
        business rather than theirs.

        Falls back to the code for a role defined directly in the CSS console,
        which carries no sidecar to read a name from. A blank line would be worse
        than a technical one.
      */
      String named = roleNames == null ? null : roleNames.get(parsed.baseRoleName());
      body.append("  - ").append(
          named == null || named.isBlank() ? parsed.baseRoleName() : named);
      if (parsed.isScoped()) {
        // The scope only exists in the generated role name, so it is read back
        // out rather than passed alongside. A role scoped by more than one thing
        // lists them all: naming only the district would tell somebody they hold
        // access they do not.
        body.append(" (")
            .append(parsed.scopes().stream()
                .map(CssRoleNaming.Scope::value)
                .collect(java.util.stream.Collectors.joining(", ")))
            .append(")");
      }
      body.append("\n");
    }

    body.append("\nThis is an automated message from Forest Access Management. ")
        .append("If you were not expecting it, contact your application administrator.\n");
    return body.toString();
  }

  private static List<CssUserRoleAssignmentResult> withStatus(
      List<CssUserRoleAssignmentResult> results, EmailSendingStatus status) {

    // Only assigned roles carry an email outcome; a role that failed to assign
    // was never going to be announced.
    return results.stream()
        .map(result -> result.assigned() ? result.withEmailStatus(status) : result)
        .toList();
  }
}
