package com.firstclub.membership.initializer;

import com.firstclub.membership.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionScheduler {

    private final SubscriptionService subscriptionService;

    /** Bulk-expires subscriptions that have passed their end date. Runs every hour. */
    @Scheduled(cron = "0 0 * * * *")
    public void expireSubscriptions() {
        log.debug("Subscription expiry job triggered");
        subscriptionService.processExpiredSubscriptions();
    }

    /** Renews auto-renewal subscriptions due within the next 24 hours. Runs daily at 6 AM. */
    @Scheduled(cron = "0 0 6 * * *")
    public void processRenewals() {
        log.debug("Subscription renewal job triggered");
        subscriptionService.processRenewals();
    }
}
