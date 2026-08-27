package ca.bc.gov.nrs.fam.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduler, which FAM needs for exactly one job.
 *
 * <p>The role expiry sweep - see
 * {@link ca.bc.gov.nrs.fam.service.RoleExpiryScheduler}. CSS has no concept of an
 * expiry and BC Gov SSO gives FAM no hook at token issuance, so removing lapsed
 * access on a timer is the only way an expiry date can mean anything.
 *
 * <p>Behind the same switch as the job itself, so a deployment that wants no
 * background work - a test environment pointed at somebody else's CSS, say -
 * turns both off with one property rather than leaving a scheduler running with
 * nothing to do.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "fam.expiry.sweep.enabled", havingValue = "true",
    matchIfMissing = true)
public class SchedulingConfiguration {}
