package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The wrapper around the expiry sweep: when it runs, and who runs it.
 *
 * <p>Thin, and every line of it fails quietly. Several pods wake at the same
 * moment, so without the lock they would each read the same lapsed markers and
 * race to remove the same assignments - duplicate CSS calls, duplicate audit
 * rows, and errors that read as faults rather than as a second pod arriving
 * first. None of that shows up as a broken screen.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RoleExpiryScheduler")
class RoleExpirySchedulerTest {

    @Mock private RoleExpirySweepService sweepService;
    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private RoleExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new RoleExpiryScheduler(sweepService, entityManager);
        setZone("America/Vancouver");

        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(sweepService.sweepAll(any()))
                .thenReturn(new RoleExpirySweepService.SweepResult(0, 0, 0));
    }

    private void setZone(String zone) {
        ReflectionTestUtils.setField(scheduler, "zone", zone);
    }

    /** What the database says when the lock is free, or already taken. */
    private void lockIsFree(boolean free) {
        when(query.getSingleResult()).thenReturn(free);
    }

    @Test
    void sweepsWhenItTakesTheLock() {
        lockIsFree(true);

        scheduler.sweep();

        verify(sweepService).sweepAll(any());
    }

    @Test
    void doesNothingWhenAnotherPodHoldsTheLock() {
        // The whole point. Two pods sweeping the same estate revoke the same
        // grants twice and write the audit twice for one expiry.
        lockIsFree(false);

        scheduler.sweep();

        verify(sweepService, never()).sweepAll(any());
    }

    @Test
    void doesNotSweepOnAnUnreadableLockAnswer() {
        // Null rather than a boolean - a driver quirk, or a query that did not
        // run. Sweeping anyway would be doing the unguarded thing precisely
        // when the guard could not be trusted.
        when(query.getSingleResult()).thenReturn(null);

        scheduler.sweep();

        verify(sweepService, never()).sweepAll(any());
    }

    @Test
    void asksForTheLockWithoutWaitingForIt() {
        /*
            The blocking form would queue every pod behind the first and then
            let each run the sweep in turn the moment it finished - which is
            exactly the duplicated work the lock exists to prevent, only
            serialised.
        */
        lockIsFree(true);

        scheduler.sweep();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());

        assertThat(sql.getValue()).contains("pg_try_advisory_xact_lock");
        assertThat(sql.getValue()).doesNotContain("pg_advisory_lock");
    }

    @Test
    void holdsTheLockForTheTransactionRatherThanForEver() throws Exception {
        /*
            A session-scoped lock outlives a pod that dies mid-sweep, and
            nothing would ever release it - the sweep would stop for good, in
            silence. The transaction-scoped form is released by the database.

            Checked through the annotation as well as the SQL: without
            @Transactional there is no transaction to scope the lock to, and it
            would be released the moment the connection returned to the pool -
            leaving the next pod free to start immediately.
        */
        Method sweep = RoleExpiryScheduler.class.getMethod("sweep");

        assertThat(sweep.isAnnotationPresent(
                org.springframework.transaction.annotation.Transactional.class))
                .as("the lock is scoped to a transaction, so there has to be one")
                .isTrue();
    }

    @Test
    void readsTodayWhereThePeopleAreRatherThanWhereThePodIs() {
        /*
            A grant made "until today" ends at the end of that day in BC. Read
            in UTC on a pod, it would end hours early for anybody who chose the
            current date.

            Two zones a day apart, so the assertion holds whatever the clock
            says when this runs: +14 and -11 are twenty-five hours apart, so
            their local dates can never agree.
        */
        lockIsFree(true);
        ArgumentCaptor<LocalDate> swept = ArgumentCaptor.forClass(LocalDate.class);

        setZone("Pacific/Kiritimati");
        scheduler.sweep();

        setZone("Pacific/Midway");
        scheduler.sweep();

        verify(sweepService, org.mockito.Mockito.times(2)).sweepAll(swept.capture());
        assertThat(swept.getAllValues().get(0))
                .isNotEqualTo(swept.getAllValues().get(1));
        assertThat(swept.getAllValues().get(0))
                .isEqualTo(LocalDate.now(ZoneId.of("Pacific/Kiritimati")));
    }

    @Test
    void wakesOnADelayMeasuredFromTheEndOfTheLastRun() throws Exception {
        /*
            A fixed delay, not a fixed rate or a cron: a sweep that overran its
            interval would otherwise be started again while still running, on
            the same pod, against the same estate.
        */
        Scheduled scheduled =
                RoleExpiryScheduler.class.getMethod("sweep").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .contains("fam.expiry.sweep.interval");
        assertThat(scheduled.fixedRateString()).isEmpty();
        assertThat(scheduled.cron()).isEmpty();
    }

    @Test
    void doesNotSweepTheInstantThePodStarts() {
        // Every pod in a rolling deploy would otherwise reach for the lock at
        // once, while the application is still warming up.
        Scheduled scheduled;
        try {
            scheduled = RoleExpiryScheduler.class
                    .getMethod("sweep")
                    .getAnnotation(Scheduled.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }

        assertThat(scheduled.initialDelayString())
                .contains("fam.expiry.sweep.initial-delay");
    }

    @Test
    void locksOnOneFixedKeySoEveryPodContendsForTheSameOne() {
        // Advisory locks share one namespace per database. A key derived from
        // anything that varies - a pod name, a timestamp - would give every pod
        // its own lock and guard nothing.
        lockIsFree(true);
        ArgumentCaptor<Object> key = ArgumentCaptor.forClass(Object.class);

        scheduler.sweep();
        scheduler.sweep();

        verify(query, org.mockito.Mockito.times(2)).setParameter(eq("key"), key.capture());
        assertThat(key.getAllValues().get(0)).isEqualTo(key.getAllValues().get(1));
    }
}
