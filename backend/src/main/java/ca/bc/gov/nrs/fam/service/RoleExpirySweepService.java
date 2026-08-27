package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.CssIntegrationDto;
import ca.bc.gov.nrs.fam.dto.CssRoleDto;
import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
import ca.bc.gov.nrs.fam.integration.CssApiService;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import ca.bc.gov.nrs.fam.security.Requester;
import org.springframework.http.HttpStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Takes away access whose day has passed.
 *
 * <p><b>Why this exists at all.</b> The legacy application never removed expired
 * access: it stored an expiry column on its own assignment table and its Cognito
 * pre-token-generation lambda left those roles out of the token. The row stayed
 * for ever. FAM has no equivalent hook - BC Gov SSO issues the tokens - so the
 * only way an expiry can mean anything here is to remove the assignment.
 *
 * <p><b>How it finds the work.</b> The date is in the sidecar's own name, so a
 * whole integration can be checked by listing its roles and reading them. No
 * user is queried until something has actually lapsed, and an integration where
 * nobody has ever granted an expiry costs exactly one request.
 *
 * <p><b>What it does not do.</b> It never removes a role that has no expiry
 * sidecar, and never one whose sidecar it could not parse. Both of those are the
 * cautious direction on purpose: this is the one background job in the system
 * that takes access away, and the failure that matters is locking somebody out
 * of something nobody asked to end.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleExpirySweepService {

  private final CssApiService cssApiService;
  private final PermissionAuditWriteService auditWriteService;

  /**
   * Who the audit trail records as having done this.
   *
   * <p>A grant is performed by a person and a sweep is not, so it is recorded as
   * {@code system} rather than borrowed from whoever happened to grant it. The
   * audit table's {@code performer_user} already allows for that.
   */
  private static final Requester SYSTEM =
      new Requester(
          null, "system", null, null, null, null, null, null, List.<String>of(), false, false);

  /** What one pass over one integration did, for logging and for tests. */
  public record SweepResult(int sidecarsExpired, int assignmentsRemoved, int failures) {
    static SweepResult empty() {
      return new SweepResult(0, 0, 0);
    }

    SweepResult plus(SweepResult other) {
      return new SweepResult(
          sidecarsExpired + other.sidecarsExpired,
          assignmentsRemoved + other.assignmentsRemoved,
          failures + other.failures);
    }
  }

  /**
   * Sweeps every integration FAM administers, in every environment it has.
   *
   * <p>One integration failing does not stop the others: a sweep that gives up
   * half way leaves the rest of the estate holding access that should have
   * ended, and the next run would hit the same failure in the same place.
   */
  public SweepResult sweepAll(LocalDate today) {
    List<CssIntegrationDto> integrations;
    try {
      integrations = cssApiService.getIntegrations();
    } catch (Exception e) {
      log.error("Expiry sweep could not list integrations; nothing was swept.", e);
      return SweepResult.empty();
    }

    SweepResult total = SweepResult.empty();
    for (CssIntegrationDto integration : integrations) {
      for (String environment : integration.environments()) {
        try {
          total = total.plus(sweep(integration.id(), environment, today));
        } catch (Exception e) {
          log.error("Expiry sweep failed for integration {} ({}).",
              integration.id(), environment, e);
          total = total.plus(new SweepResult(0, 0, 1));
        }
      }
    }
    return total;
  }

  /**
   * Sweeps one integration in one environment.
   *
   * <p>An expiry is the <em>last</em> day the access is good for, so it lapses
   * once today is past it - a grant expiring today is still live all day.
   */
  public SweepResult sweep(int integrationId, String environment, LocalDate today) {
    List<CssRoleDto> roles = cssApiService.getRoles(integrationId, environment);

    SweepResult result = SweepResult.empty();

    for (CssRoleDto role : roles) {
      Optional<CssRoleNaming.RoleExpiry> parsed = CssRoleNaming.parseExpiry(role.name());
      if (parsed.isEmpty()) {
        continue;
      }
      CssRoleNaming.RoleExpiry expiry = parsed.get();
      if (!today.isAfter(expiry.expiresOn())) {
        continue;
      }
      result = result.plus(revokeHolders(integrationId, environment, role.name(), expiry));
    }

    if (result.assignmentsRemoved() > 0 || result.failures() > 0) {
      log.info("Expiry sweep of integration {} ({}): {} lapsed, {} removed, {} failed.",
          integrationId, environment,
          result.sidecarsExpired(), result.assignmentsRemoved(), result.failures());
    }
    return result;
  }

  /**
   * Removes one lapsed grant from everybody still holding it.
   *
   * <p>The governed role goes first. If the run dies between the two removals,
   * what is left behind is a sidecar with nobody's access attached - untidy, and
   * swept again next time - rather than access whose expiry marker has gone,
   * which nothing would ever come back for.
   */
  private SweepResult revokeHolders(
      int integrationId, String environment, String sidecarName,
      CssRoleNaming.RoleExpiry expiry) {

    List<CssApiService.CssUserDto> holders;
    try {
      holders = cssApiService.getUsersWithRole(integrationId, environment, sidecarName);
    } catch (Exception e) {
      log.error("Could not read who holds {}; leaving it for the next sweep.", sidecarName, e);
      return new SweepResult(1, 0, 1);
    }

    int removed = 0;
    int failed = 0;

    for (CssApiService.CssUserDto holder : holders) {
      try {
        /*
          Not fatal if the access has already gone.

          A marker can outlive the role it governs - somebody revoking by hand
          before this ran, or a marker orphaned by an older version of the
          revoke path. Treating that as a failure kept the marker, so the next
          sweep found it, failed the same way, and kept it again: an error every
          half hour for ever, over access that was already correct.
        */
        boolean wasHeld = removeIfHeld(
            integrationId, environment, holder.username(), expiry.roleName());

        cssApiService.removeUserRole(
            integrationId, environment, holder.username(), sidecarName);

        if (wasHeld) {
          recordRevoked(integrationId, environment, holder, expiry);
          removed++;
        }
      } catch (Exception e) {
        // Left in place deliberately. The next sweep will find the sidecar
        // exactly as it is and try again; the alternative - dropping the
        // sidecar after a failed removal - would abandon live access with
        // nothing left to say it should have ended.
        log.error("Could not expire {} for {}; it stays until the next sweep.",
            expiry.roleName(), holder.username(), e);
        failed++;
      }
    }
    return new SweepResult(1, removed, failed);
  }

  /**
   * Removes one role, reporting whether it was there to remove.
   *
   * <p>False rather than an exception for an assignment that has already gone:
   * nothing happened, so there is nothing to record and nothing to retry.
   */
  private boolean removeIfHeld(
      int integrationId, String environment, String username, String roleName) {
    try {
      cssApiService.removeUserRole(integrationId, environment, username, roleName);
      return true;
    } catch (UpstreamException e) {
      /*
        Only a 404 counts as "already gone". Anything else - CSS refusing,
        CSS down - is a real failure and has to propagate, or a transient
        outage would be read as a completed removal and the marker dropped,
        abandoning access that is still live with nothing left to end it.
      */
      if (e.getStatus() != HttpStatus.NOT_FOUND) {
        throw e;
      }
      log.info("{} no longer holds {}; only the expiry marker is left to tidy.",
          username, roleName);
      return false;
    }
  }

  /**
   * Writes the audit row.
   *
   * <p>Deliberately not fatal. The access is already gone by the time this runs,
   * and throwing here would abandon the rest of the sweep to preserve a record of
   * something that has already happened. It is logged at error so an unrecorded
   * removal is still visible.
   */
  private void recordRevoked(
      int integrationId, String environment, CssApiService.CssUserDto holder,
      CssRoleNaming.RoleExpiry expiry) {

    try {
      auditWriteService.storeCssRevoked(
          SYSTEM,
          CssRoleNaming.guidFromUsername(holder.username()).orElse(null),
          CssRoleNaming.domainFromUsername(holder.username())
              .map(RoleExpirySweepService::userTypeOf)
              .orElse(null),
          integrationId,
          environment,
          CssRoleNaming.parse(expiry.roleName()).baseRoleName(),
          List.of(expiry.roleName()));
    } catch (Exception e) {
      log.error("Expired {} for {} but could not record it in the audit trail.",
          expiry.roleName(), holder.username(), e);
    }
  }

  private static UserType userTypeOf(String domain) {
    return "IDIR".equalsIgnoreCase(domain) ? UserType.IDIR : UserType.BCEID;
  }
}
