package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.EmailSendingStatus;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentResult;
import ca.bc.gov.nrs.fam.integration.EmailService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The "you have been granted access" notification.
 *
 * <p>The rule carried over from upstream: a failed notification is reported on
 * the result, never raised. A grant that succeeded is not undone by an email that
 * did not arrive.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccessGrantedEmailService")
class AccessGrantedEmailServiceTest {

  private static final String EMAIL = "jane@gov.bc.ca";
  private static final String APP = "integration 22264 (DEV)";

  @Mock private EmailService emailService;
  @InjectMocks private AccessGrantedEmailService service;

  private static CssUserRoleAssignmentResult assigned(String roleName) {
    return new CssUserRoleAssignmentResult(
        roleName, true, true, null, EmailSendingStatus.NOT_REQUIRED);
  }

  private static CssUserRoleAssignmentResult failed(String roleName) {
    return new CssUserRoleAssignmentResult(
        roleName, false, false, "boom", EmailSendingStatus.NOT_REQUIRED);
  }

  private void configured(boolean sendSucceeds) {
    when(emailService.isConfigured()).thenReturn(true);
    when(emailService.send(any(), anyString(), anyString())).thenReturn(sendSucceeds);
  }

  private String captureBody() {
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(emailService).send(any(), anyString(), body.capture());
    return body.getValue();
  }

  @Test
  @DisplayName("notifies the address supplied on the request")
  void notifiesSuppliedAddress() {
    configured(true);

    List<CssUserRoleAssignmentResult> results =
        service.notifyGranted(EMAIL, APP, List.of(assigned("FREP_ADMINISTRATOR")));

    ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
    verify(emailService).send(to.capture(), anyString(), anyString());
    assertThat(to.getValue()).containsExactly(EMAIL);
    assertThat(results).allSatisfy(r ->
        assertThat(r.emailSendingStatus())
            .isEqualTo(EmailSendingStatus.SENT_TO_EMAIL_SERVICE_SUCCESS));
  }

  @Test
  @DisplayName("sends nothing when no role was actually assigned")
  void sendsNothingWhenNothingGranted() {
    // No "you have been granted nothing" email.
    service.notifyGranted(EMAIL, APP, List.of(failed("R")));

    verify(emailService, never()).send(any(), anyString(), anyString());
  }

  @Test
  @DisplayName("records a relay failure on the result rather than raising it")
  void relayFailureIsRecorded() {
    // The grant already happened. An administrator needs to see "granted, but
    // not emailed", not an error suggesting the grant failed.
    configured(false);

    assertThat(service.notifyGranted(EMAIL, APP, List.of(assigned("R"))))
        .allSatisfy(r -> assertThat(r.emailSendingStatus())
            .isEqualTo(EmailSendingStatus.SENT_TO_EMAIL_SERVICE_FAILURE));
  }

  @Test
  @DisplayName("records a thrown failure rather than propagating it")
  void thrownFailureIsRecorded() {
    when(emailService.isConfigured()).thenReturn(true);
    when(emailService.send(any(), anyString(), anyString()))
        .thenThrow(new RuntimeException("relay exploded"));

    assertThat(service.notifyGranted(EMAIL, APP, List.of(assigned("R"))))
        .allSatisfy(r -> assertThat(r.emailSendingStatus())
            .isEqualTo(EmailSendingStatus.SENT_TO_EMAIL_SERVICE_FAILURE));
  }

  @Test
  @DisplayName("a missing address is not-required, not a failure")
  void missingAddressIsNotAFailure() {
    // Nothing to retry, and the grant is unaffected.
    when(emailService.isConfigured()).thenReturn(true);

    assertThat(service.notifyGranted(null, APP, List.of(assigned("R"))))
        .allSatisfy(r -> assertThat(r.emailSendingStatus())
            .isEqualTo(EmailSendingStatus.NOT_REQUIRED));
    verify(emailService, never()).send(any(), anyString(), anyString());
  }

  @Test
  @DisplayName("an unconfigured relay is not-required, not a failure")
  void unconfiguredRelayIsNotAFailure() {
    when(emailService.isConfigured()).thenReturn(false);

    assertThat(service.notifyGranted(EMAIL, APP, List.of(assigned("R"))))
        .allSatisfy(r -> assertThat(r.emailSendingStatus())
            .isEqualTo(EmailSendingStatus.NOT_REQUIRED));
  }

  @Test
  @DisplayName("names each granted role, with its scope value")
  void namesGrantedRolesAndScopes() {
    // The scope only exists inside the generated role name, so it is read back
    // out for the message.
    configured(true);

    service.notifyGranted(EMAIL, APP, List.of(
        assigned("CHR_FREP_EDITOR_DISTRICT-DCC"),
        assigned("CHR_FREP_EDITOR_DISTRICT-DQU")));

    assertThat(captureBody())
        .contains("CHR_FREP_EDITOR")
        .contains("(DCC)")
        .contains("(DQU)");
  }

  @Test
  @DisplayName("does not announce roles that failed to assign")
  void doesNotAnnounceFailedRoles() {
    // Telling someone they have access they were not given is worse than saying
    // nothing.
    configured(true);

    service.notifyGranted(EMAIL, APP, List.of(
        assigned("R_DISTRICT-DCC"), failed("R_DISTRICT-DQU")));

    assertThat(captureBody()).contains("DCC").doesNotContain("DQU");
  }

  @Test
  @DisplayName("leaves a failed role's email status alone")
  void failedRoleKeepsItsStatus() {
    configured(true);

    assertThat(service.notifyGranted(EMAIL, APP, List.of(assigned("R1"), failed("R2"))))
        .filteredOn(r -> r.roleName().equals("R2"))
        .singleElement()
        .satisfies(r -> assertThat(r.emailSendingStatus())
            .isEqualTo(EmailSendingStatus.NOT_REQUIRED));
  }
}
