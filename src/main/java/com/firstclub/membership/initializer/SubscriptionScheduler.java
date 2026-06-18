package com.firstclub.membership.initializer;

import com.firstclub.membership.service.EarnedTierService;
import com.firstclub.membership.service.SubscriptionService;
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

    /** Bulk-expires subscriptions that have passed their end date. Runs every hour. */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "expireSubscriptions", lockAtMostFor = "10m", lockAtLeastFor = "30s")
    public void expireSubscriptions() {
        log.debug("Subscription expiry job triggered");
        subscriptionService.processExpiredSubscriptions();
    }

    /** Renews auto-renewal subscriptions due within the next 24 hours. Runs daily at 6 AM. */
    @Scheduled(cron = "0 0 6 * * *")
    @SchedulerLock(name = "processRenewals", lockAtMostFor = "30m", lockAtLeastFor = "1m")
    public void processRenewals() {
        log.debug("Subscription renewal job triggered");
        subscriptionService.processRenewals();
    }

    /** Converts (or expires) trials whose window has ended. Runs hourly. */
    @Scheduled(cron = "0 30 * * * *")
    @SchedulerLock(name = "processTrialConversions", lockAtMostFor = "30m", lockAtLeastFor = "30s")
    public void processTrialConversions() {
        log.debug("Trial conversion job triggered");
        subscriptionService.processTrialConversions();
    }

    /** Recomputes every user's earned tier from their order activity. Runs daily at 2 AM. */
    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "reevaluateEarnedTiers", lockAtMostFor = "30m", lockAtLeastFor = "1m")
    public void reevaluateEarnedTiers() {
        log.debug("Earned-tier re-evaluation job triggered");
        earnedTierService.reevaluateAll();
    }
}
