package com.firstclub.membership.initializer;

import com.firstclub.membership.service.EarnedTierService;
import com.firstclub.membership.service.SubscriptionService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionScheduler {

    private final SubscriptionService subscriptionService;
    private final EarnedTierService earnedTierService;
    private final MeterRegistry meterRegistry;

    /** Bulk-expires subscriptions that have passed their end date. Runs every hour. */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "expireSubscriptions", lockAtMostFor = "10m", lockAtLeastFor = "30s")
    public void expireSubscriptions() {
        timed("expireSubscriptions", subscriptionService::processExpiredSubscriptions);
    }

    /** Renews auto-renewal subscriptions due within the next 24 hours. Runs daily at 6 AM. */
    @Scheduled(cron = "0 0 6 * * *")
    @SchedulerLock(name = "processRenewals", lockAtMostFor = "30m", lockAtLeastFor = "1m")
    public void processRenewals() {
        timed("processRenewals", subscriptionService::processRenewals);
    }

    /** Converts (or expires) trials whose window has ended. Runs hourly. */
    @Scheduled(cron = "0 30 * * * *")
    @SchedulerLock(name = "processTrialConversions", lockAtMostFor = "30m", lockAtLeastFor = "30s")
    public void processTrialConversions() {
        timed("processTrialConversions", subscriptionService::processTrialConversions);
    }

    /** Recomputes every user's earned tier from their order activity. Runs daily at 2 AM. */
    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "reevaluateEarnedTiers", lockAtMostFor = "30m", lockAtLeastFor = "1m")
    public void reevaluateEarnedTiers() {
        timed("reevaluateEarnedTiers", earnedTierService::reevaluateAll);
    }

    /**
     * Runs a scheduled job under observability: records its duration as
     * {@code membership.scheduler.run{job=...}} (a timer — count, total time, max) and its failures
     * as {@code membership.scheduler.failures{job=...}}. Exceptions are re-thrown so ShedLock and the
     * scheduler see the original outcome; only the surrounding instrumentation is added.
     */
    private void timed(String job, Runnable task) {
        log.debug("Scheduler job triggered: {}", job);
        try {
            meterRegistry.timer("membership.scheduler.run", "job", job).record(task);
        } catch (RuntimeException e) {
            meterRegistry.counter("membership.scheduler.failures", "job", job).increment();
            log.error("Scheduler job {} failed", job, e);
            throw e;
        }
    }
}
