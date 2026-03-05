package com.firstclub.membership.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled background jobs for subscription lifecycle management.
 *
 * Cron expressions are externalized to application.properties so they can be
 * adjusted per environment without rebuilding (membership.scheduler.*).
 *
 * Both jobs run nightly by default (01:00 and 01:05) to minimize impact on
 * traffic while keeping subscription states accurate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulerService {

    private final MembershipService membershipService;

    /**
     * Marks subscriptions whose endDate has passed as EXPIRED.
     * Runs nightly at 01:00 by default.
     */
    @Scheduled(cron = "${membership.scheduler.expire-subscriptions:0 0 1 * * *}")
    public void processExpiredSubscriptions() {
        log.info("[Scheduler] Starting nightly expired-subscription sweep...");
        try {
            membershipService.processExpiredSubscriptions();
            log.info("[Scheduler] Expired-subscription sweep completed.");
        } catch (Exception e) {
            log.error("[Scheduler] Error during expired-subscription sweep", e);
        }
    }

    /**
     * Auto-renews subscriptions whose nextBillingDate is due.
     * Runs nightly at 01:05 by default (after expiry sweep).
     */
    @Scheduled(cron = "${membership.scheduler.process-renewals:0 5 1 * * *}")
    public void processRenewals() {
        log.info("[Scheduler] Starting nightly renewal sweep...");
        try {
            membershipService.processRenewals();
            log.info("[Scheduler] Renewal sweep completed.");
        } catch (Exception e) {
            log.error("[Scheduler] Error during renewal sweep", e);
        }
    }
}
