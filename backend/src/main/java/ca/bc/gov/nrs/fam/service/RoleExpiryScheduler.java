package ca.bc.gov.nrs.fam.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the expiry sweep, on one pod at a time.
 *
 * <p>Separate from {@link RoleExpirySweepService} so the sweep itself stays a
 * plain method: it can be called from a test, or by hand, without a clock or a
 * lock in the way.
 *
 * <p><b>The lock.</b> FAM runs several pods and they would all wake at the same
 * moment, so without one they would each read the same lapsed sidecars and race
 * to remove the same assignments - duplicate CSS calls, duplicate audit rows, and
 * errors that look like faults rather than a second pod arriving first. A
 * Postgres advisory lock is what is used because it needs nothing: no table, no
 * migration, no new dependency. It is held for the length of the transaction and
 * released by the database if the pod dies mid-sweep, which a lock table would
 * not do without a lease and a reaper.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fam.expiry.sweep.enabled", havingValue = "true",
    matchIfMissing = true)
public class RoleExpiryScheduler {

  /**
   * An arbitrary but fixed key, unique to this job within the database.
   *
   * <p>Advisory locks share one namespace per database, so the number only has to
   * differ from anything else that takes one. Nothing else in FAM does today.
   */
  private static final long SWEEP_LOCK_KEY = 8_251_140_014L;

  private final RoleExpirySweepService sweepService;
  private final EntityManager entityManager;

  @Value("${fam.expiry.sweep.zone:America/Vancouver}")
  private String zone;

  /**
   * Wakes on a fixed delay rather than a cron.
   *
   * <p>A fixed delay measures from the end of the previous run, so a slow sweep
   * cannot overlap the next one on the same pod. The cadence is worth being
   * unhurried about: an expiry is a date, so being minutes late is invisible, and
   * running often only multiplies the requests made to CSS for nothing.
   */
  @Scheduled(
      initialDelayString = "${fam.expiry.sweep.initial-delay:PT2M}",
      fixedDelayString = "${fam.expiry.sweep.interval:PT30M}")
  @Transactional
  public void sweep() {
    if (!acquireLock()) {
      log.debug("Another pod is running the expiry sweep; skipping this turn.");
      return;
    }

    // Today in BC, not in the pod's zone: the date somebody picked meant a day
    // where they are, and a container on UTC would end access several hours
    // early for anybody who chose the current day.
    LocalDate today = LocalDate.now(java.time.ZoneId.of(zone));

    RoleExpirySweepService.SweepResult result = sweepService.sweepAll(today);

    if (result.assignmentsRemoved() > 0 || result.failures() > 0) {
      log.info("Expiry sweep for {}: {} lapsed, {} removed, {} failed.",
          today, result.sidecarsExpired(), result.assignmentsRemoved(), result.failures());
    }
  }

  /**
   * Takes the lock, or reports that somebody else holds it.
   *
   * <p>{@code pg_try_advisory_xact_lock} rather than the blocking form: a pod
   * that waited would run the sweep again the moment the first finished, which is
   * the duplicate work the lock exists to prevent.
   */
  private boolean acquireLock() {
    return Boolean.TRUE.equals(
        entityManager
            .createNativeQuery("SELECT pg_try_advisory_xact_lock(:key)")
            .setParameter("key", SWEEP_LOCK_KEY)
            .getSingleResult());
  }
}
