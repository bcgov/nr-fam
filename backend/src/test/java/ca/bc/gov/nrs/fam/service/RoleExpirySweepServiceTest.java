package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.dto.CssIntegrationDto;
import ca.bc.gov.nrs.fam.dto.CssRoleDto;
import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import ca.bc.gov.nrs.fam.integration.CssApiService;
import org.springframework.http.HttpStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The sweep that removes access whose day has passed.
 *
 * <p>This is the only background job in FAM that takes access away, so what is
 * tested hardest is what it must <em>not</em> do: touch a role with no expiry,
 * act on a name it could not read, or drop the marker for a removal that failed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleExpirySweepServiceTest {

  private static final int INTEGRATION = 6538;
  private static final String ENV = "dev";
  private static final String GRANTED = "FREP_EDITOR_DISTRICT-DCC";
  private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

  @Mock private CssApiService cssApiService;
  @Mock private PermissionAuditWriteService auditWriteService;

  @InjectMocks private RoleExpirySweepService service;

  private static CssApiService.CssUserDto holder(String username) {
    return new CssApiService.CssUserDto(username, "Jane", "Smith", "j@e.ca", Map.of());
  }

  private static CssRoleDto role(String name) {
    return new CssRoleDto(name, false);
  }

  private String sidecarFor(LocalDate expiresOn) {
    return CssRoleNaming.buildExpiryRoleName(expiresOn, GRANTED);
  }

  private void integrationHas(CssRoleDto... roles) {
    when(cssApiService.getRoles(INTEGRATION, ENV)).thenReturn(List.of(roles));
  }

  @Nested
  @DisplayName("what it removes")
  class Removes {

    @Test
    void takesAwayAGrantWhoseDayHasPassed() {
      String sidecar = sidecarFor(TODAY.minusDays(1));
      integrationHas(role(GRANTED), role(sidecar));
      when(cssApiService.getUsersWithRole(INTEGRATION, ENV, sidecar))
          .thenReturn(List.of(holder("abc@azureidir")));

      RoleExpirySweepService.SweepResult result = service.sweep(INTEGRATION, ENV, TODAY);

      verify(cssApiService).removeUserRole(INTEGRATION, ENV, "abc@azureidir", GRANTED);
      assertThat(result.assignmentsRemoved()).isEqualTo(1);
    }

    @Test
    void removesTheMarkerAsWellAsTheAccess() {
      String sidecar = sidecarFor(TODAY.minusDays(1));
      integrationHas(role(sidecar));
      when(cssApiService.getUsersWithRole(INTEGRATION, ENV, sidecar))
          .thenReturn(List.of(holder("abc@azureidir")));

      service.sweep(INTEGRATION, ENV, TODAY);

      // Left behind, it would be found and acted on again every half hour for
      // access that has already gone.
      verify(cssApiService).removeUserRole(INTEGRATION, ENV, "abc@azureidir", sidecar);
    }

    @Test
    void takesItFromEverybodyHoldingIt() {
      String sidecar = sidecarFor(TODAY.minusDays(1));
      integrationHas(role(sidecar));
      when(cssApiService.getUsersWithRole(INTEGRATION, ENV, sidecar))
          .thenReturn(List.of(holder("a@azureidir"), holder("b@azureidir")));

      assertThat(service.sweep(INTEGRATION, ENV, TODAY).assignmentsRemoved()).isEqualTo(2);
    }

    @Test
    void recordsItAgainstTheSystemRatherThanAPerson() {
      String sidecar = sidecarFor(TODAY.minusDays(1));
      integrationHas(role(sidecar));
      when(cssApiService.getUsersWithRole(INTEGRATION, ENV, sidecar))
          .thenReturn(List.of(holder("abc@azureidir")));

      service.sweep(INTEGRATION, ENV, TODAY);

      // CSS keeps no history, so without this row nothing anywhere says the
      // access ended or why.
      verify(auditWriteService).storeCssRevoked(
          any(), anyString(), any(), eq(INTEGRATION), eq(ENV), anyString(), any());
    }
  }

  @Nested
  @DisplayName("what it leaves alone")
  class LeavesAlone {

    @Test
    void aGrantExpiringTodayIsStillGoodAllDay() {
      // The date is the last day the access is good for, which is how the
      // legacy application read the same field. Expiring at the start of the
      // day would cut everybody a day short of what they were promised.
      String sidecar = sidecarFor(TODAY);
      integrationHas(role(sidecar));
      // Somebody actually holds it, so "nothing was removed" is a decision
      // rather than an empty list.
      when(cssApiService.getUsersWithRole(INTEGRATION, ENV, sidecar))
          .thenReturn(List.of(holder("abc@azureidir")));

      RoleExpirySweepService.SweepResult result = service.sweep(INTEGRATION, ENV, TODAY);

      verify(cssApiService, never()).removeUserRole(anyInt(), anyString(), anyString(), anyString());
      assertThat(result.assignmentsRemoved()).isZero();
    }

    @Test
    void aGrantWithNoExpiryAtAll() {
      integrationHas(role(GRANTED), role("FREP_VIEWER"));
      when(cssApiService.getUsersWithRole(anyInt(), anyString(), anyString()))
          .thenReturn(List.of(holder("abc@azureidir")));

      service.sweep(INTEGRATION, ENV, TODAY);

      verify(cssApiService, never()).getUsersWithRole(anyInt(), anyString(), anyString());
      verify(cssApiService, never()).removeUserRole(anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void aMarkerItCouldNotRead() {
      // A hand-made role that merely starts with the prefix. Guessing at it
      // would mean removing access on the strength of a name nobody wrote for
      // this purpose.
      String malformed = "FAM:EXPIRES:not-a-date:FREP_EDITOR";
      integrationHas(role(malformed));
      when(cssApiService.getUsersWithRole(INTEGRATION, ENV, malformed))
          .thenReturn(List.of(holder("abc@azureidir")));

      service.sweep(INTEGRATION, ENV, TODAY);

      verify(cssApiService, never()).removeUserRole(anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void theOtherSidecars() {
      integrationHas(
          role("FAM:LABEL:FREP_EDITOR:Editor"),
          role("FAM:DESC:FREP_EDITOR:Edits things"));
      when(cssApiService.getUsersWithRole(anyInt(), anyString(), anyString()))
          .thenReturn(List.of(holder("abc@azureidir")));

      service.sweep(INTEGRATION, ENV, TODAY);

      verify(cssApiService, never()).removeUserRole(anyInt(), anyString(), anyString(), anyString());
    }
  }

  @Nested
  @DisplayName("when something goes wrong")
  class Failures {

    @Test
    void keepsTheMarkerWhenTheRemovalFailed() {
      String sidecar = sidecarFor(TODAY.minusDays(1));
      integrationHas(role(sidecar));
      when(cssApiService.getUsersWithRole(INTEGRATION, ENV, sidecar))
          .thenReturn(List.of(holder("abc@azureidir")));
      doThrow(new RuntimeException("CSS is down"))
          .when(cssApiService).removeUserRole(INTEGRATION, ENV, "abc@azureidir", GRANTED);

      RoleExpirySweepService.SweepResult result = service.sweep(INTEGRATION, ENV, TODAY);

      // Dropping the marker here would abandon live access with nothing left to
      // say it should have ended - nothing would ever come back for it.
      verify(cssApiService, never()).removeUserRole(INTEGRATION, ENV, "abc@azureidir", sidecar);
      assertThat(result.failures()).isEqualTo(1);
    }

    @Test
    void tidiesAMarkerWhoseAccessHasAlreadyGone() {
      /*
        An orphan: the role was revoked by hand, or by a version of the revoke
        path that left the marker behind. Counting that as a failure kept the
        marker, so the next sweep found it, failed the same way and kept it
        again - an error every half hour, for ever, over access that was already
        correct.
      */
      String sidecar = sidecarFor(TODAY.minusDays(1));
      integrationHas(role(sidecar));
      when(cssApiService.getUsersWithRole(INTEGRATION, ENV, sidecar))
          .thenReturn(List.of(holder("abc@azureidir")));
      doThrow(new UpstreamException(
              HttpStatus.NOT_FOUND, "NOT_FOUND", "no such assignment", "CSS"))
          .when(cssApiService).removeUserRole(INTEGRATION, ENV, "abc@azureidir", GRANTED);

      RoleExpirySweepService.SweepResult result = service.sweep(INTEGRATION, ENV, TODAY);

      // The marker goes, and nothing is reported as failed or removed: there
      // was no access left to take away.
      verify(cssApiService).removeUserRole(INTEGRATION, ENV, "abc@azureidir", sidecar);
      assertThat(result.failures()).isZero();
      assertThat(result.assignmentsRemoved()).isZero();
    }

    @Test
    void keepsTheMarkerWhenCssIsSimplyUnreachable() {
      // Not a 404. Reading an outage as a completed removal would drop the
      // marker and abandon access that is still live.
      String sidecar = sidecarFor(TODAY.minusDays(1));
      integrationHas(role(sidecar));
      when(cssApiService.getUsersWithRole(INTEGRATION, ENV, sidecar))
          .thenReturn(List.of(holder("abc@azureidir")));
      doThrow(new UpstreamException(
              HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM", "CSS is down", "CSS"))
          .when(cssApiService).removeUserRole(INTEGRATION, ENV, "abc@azureidir", GRANTED);

      RoleExpirySweepService.SweepResult result = service.sweep(INTEGRATION, ENV, TODAY);

      verify(cssApiService, never())
          .removeUserRole(INTEGRATION, ENV, "abc@azureidir", sidecar);
      assertThat(result.failures()).isEqualTo(1);
    }

    @Test
    void carriesOnToTheNextPersonAfterOneFails() {
      String sidecar = sidecarFor(TODAY.minusDays(1));
      integrationHas(role(sidecar));
      when(cssApiService.getUsersWithRole(INTEGRATION, ENV, sidecar))
          .thenReturn(List.of(holder("a@azureidir"), holder("b@azureidir")));
      doThrow(new RuntimeException("CSS is down"))
          .when(cssApiService).removeUserRole(INTEGRATION, ENV, "a@azureidir", GRANTED);

      RoleExpirySweepService.SweepResult result = service.sweep(INTEGRATION, ENV, TODAY);

      verify(cssApiService).removeUserRole(INTEGRATION, ENV, "b@azureidir", GRANTED);
      assertThat(result.assignmentsRemoved()).isEqualTo(1);
      assertThat(result.failures()).isEqualTo(1);
    }

    @Test
    void stillRemovesAccessWhenTheAuditWriteFails() {
      String sidecar = sidecarFor(TODAY.minusDays(1));
      integrationHas(role(sidecar));
      when(cssApiService.getUsersWithRole(INTEGRATION, ENV, sidecar))
          .thenReturn(List.of(holder("abc@azureidir")));
      doThrow(new RuntimeException("database is down"))
          .when(auditWriteService)
          .storeCssRevoked(any(), anyString(), any(), anyInt(), anyString(), anyString(), any());

      RoleExpirySweepService.SweepResult result = service.sweep(INTEGRATION, ENV, TODAY);

      // The access is already gone by then. Failing the sweep to protect a
      // record of something that has happened would abandon the rest of it.
      assertThat(result.assignmentsRemoved()).isEqualTo(1);
    }

    @Test
    void oneIntegrationFailingDoesNotStopTheRest() {
      when(cssApiService.getIntegrations()).thenReturn(List.of(
          new CssIntegrationDto(1, "A", "browser", List.of("dev"), "ok", null, null),
          new CssIntegrationDto(2, "B", "browser", List.of("dev"), "ok", null, null)));
      when(cssApiService.getRoles(1, "dev")).thenThrow(new RuntimeException("CSS is down"));
      when(cssApiService.getRoles(2, "dev")).thenReturn(List.of(role(sidecarFor(TODAY.minusDays(1)))));
      when(cssApiService.getUsersWithRole(eq(2), anyString(), anyString()))
          .thenReturn(List.of(holder("abc@azureidir")));

      RoleExpirySweepService.SweepResult result = service.sweepAll(TODAY);

      // Giving up on the first failure would leave the rest of the estate
      // holding access that should have ended, and the next run would stop in
      // exactly the same place.
      assertThat(result.assignmentsRemoved()).isEqualTo(1);
      assertThat(result.failures()).isEqualTo(1);
    }

    @Test
    void sweepsNothingWhenTheIntegrationListIsUnreachable() {
      when(cssApiService.getIntegrations()).thenThrow(new RuntimeException("CSS is down"));

      assertThat(service.sweepAll(TODAY).assignmentsRemoved()).isZero();
    }
  }
}
