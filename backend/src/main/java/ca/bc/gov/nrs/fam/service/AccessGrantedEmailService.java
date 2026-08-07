package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.EmailSendingStatus;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.FamAccessControlPrivilegeCreateResponse;
import ca.bc.gov.nrs.fam.dto.FamForestClientDto;
import ca.bc.gov.nrs.fam.dto.FamUserRoleAssignmentCreateResponse;
import ca.bc.gov.nrs.fam.dto.GcNotifyGrantAccessEmailParams;
import ca.bc.gov.nrs.fam.dto.TargetUser;
import ca.bc.gov.nrs.fam.integration.GcNotifyEmailService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Notifies users that they have been granted access.
 *
 * <p>Port of {@code send_users_access_granted_emails}. One email per user
 * summarising every role they were granted in the request, not one per
 * assignment.
 *
 * <p>Sending is <strong>best-effort</strong>: the access has already been granted
 * and committed, so a failure to notify is recorded on the response as
 * {@code SENT_TO_EMAIL_SERVICE_FAILURE} rather than raised. Failing the request
 * here would tell the administrator the grant did not happen when it did.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessGrantedEmailService {

  private final GcNotifyEmailService gcNotifyEmailService;

  /**
   * @return the same responses, with {@code emailSendingStatus} set on those that
   *     an email was attempted for
   */
  public List<FamUserRoleAssignmentCreateResponse> sendAccessGrantedEmails(
      List<TargetUser> targetUsers, List<FamUserRoleAssignmentCreateResponse> responses) {

    Map<String, TargetUser> targetUsersByName = targetUsers.stream()
        .collect(Collectors.toMap(TargetUser::userName, Function.identity(), (a, b) -> a));

    // Group by the user each assignment belongs to, preserving request order.
    Map<String, List<FamUserRoleAssignmentCreateResponse>> byUserName = new LinkedHashMap<>();
    for (FamUserRoleAssignmentCreateResponse response : responses) {
      if (response.detail() != null && response.detail().user() != null) {
        byUserName
            .computeIfAbsent(response.detail().user().userName(), k -> new ArrayList<>())
            .add(response);
      }
    }

    Map<FamUserRoleAssignmentCreateResponse, EmailSendingStatus> statuses = new LinkedHashMap<>();

    byUserName.forEach((userName, userResponses) -> {
      List<FamUserRoleAssignmentCreateResponse> successes = userResponses.stream()
          .filter(FamUserRoleAssignmentCreateResponse::isSuccess)
          .toList();

      if (successes.isEmpty()) {
        log.debug("No successful role assignments for {}; skipping email", userName);
        return;
      }

      TargetUser targetUser = targetUsersByName.get(userName);
      try {
        gcNotifyEmailService.sendUserAccessGrantedEmail(buildParams(targetUser, successes));
        log.debug("Access granted email sent for {}", userName);
        successes.forEach(r ->
            statuses.put(r, EmailSendingStatus.SENT_TO_EMAIL_SERVICE_SUCCESS));
      } catch (Exception e) {
        // The grant stands; only the notification failed.
        log.warn("Failed to send email to user_name: {}. Reason: {}", userName, e.getMessage());
        successes.forEach(r ->
            statuses.put(r, EmailSendingStatus.SENT_TO_EMAIL_SERVICE_FAILURE));
      }
    });

    return responses.stream()
        .map(r -> statuses.containsKey(r) ? r.withEmailStatus(statuses.get(r)) : r)
        .toList();
  }

  /**
   * Notify a new delegated administrator.
   *
   * <p>One email covering every role granted in the request, so the status is
   * reported once rather than per assignment. Best-effort, like the end-user
   * notification: the privilege is already committed.
   *
   * @return whether the hand-off to GC Notify succeeded
   */
  public EmailSendingStatus sendDelegatedAdminGrantedEmail(
      TargetUser targetUser, List<FamAccessControlPrivilegeCreateResponse> assignments) {

    List<FamAccessControlPrivilegeCreateResponse> successes = assignments.stream()
        .filter(FamAccessControlPrivilegeCreateResponse::isSuccess)
        .toList();

    if (successes.isEmpty()) {
      return EmailSendingStatus.NOT_REQUIRED;
    }

    try {
      var grantedRole = successes.get(0).detail().role();
      boolean scoped = grantedRole.forestClient() != null;

      List<FamForestClientDto> organizations = scoped
          ? successes.stream().map(r -> r.detail().role().forestClient()).toList()
          : null;

      GcNotifyGrantAccessEmailParams params = new GcNotifyGrantAccessEmailParams(
          targetUser.userName(),
          targetUser.firstName(),
          targetUser.lastName(),
          grantedRole.application().applicationDescription(),
          grantedRole.displayName(),
          organizations,
          null,
          targetUser.email());

      // Only BCeID delegated admins are asked to accept the terms of use.
      boolean isBceid = UserType.BCEID.getCode().equals(targetUser.userTypeCode());
      gcNotifyEmailService.sendDelegatedAdminGrantedEmail(params, isBceid);

      return EmailSendingStatus.SENT_TO_EMAIL_SERVICE_SUCCESS;

    } catch (Exception e) {
      log.warn("Failed to send delegated admin email to {}. Reason: {}",
          targetUser.userName(), e.getMessage());
      return EmailSendingStatus.SENT_TO_EMAIL_SERVICE_FAILURE;
    }
  }

  private GcNotifyGrantAccessEmailParams buildParams(
      TargetUser targetUser, List<FamUserRoleAssignmentCreateResponse> successes) {

    var grantedRole = successes.get(0).detail().role();
    boolean scoped = grantedRole.forestClient() != null;

    // Null, not empty, for an unscoped role - the email template branches on it.
    List<FamForestClientDto> organizations = scoped
        ? successes.stream().map(r -> r.detail().role().forestClient()).toList()
        : null;

    return new GcNotifyGrantAccessEmailParams(
        targetUser.userName(),
        targetUser.firstName(),
        targetUser.lastName(),
        grantedRole.application().applicationDescription(),
        grantedRole.displayName(),
        organizations,
        // Upstream TODO #1507: per-application contact address is not modelled yet.
        null,
        targetUser.email());
  }
}
