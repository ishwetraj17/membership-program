package com.firstclub.reporting.projections.scheduler;

import com.firstclub.reporting.projections.service.LedgerSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Drives a daily ledger balance snapshot via Spring's task scheduler.
 *
 * <p>Disabled by default. Enable by setting:
 * <pre>
 *   projections.snapshot.scheduler.enabled=true
 * </pre>
 * in {@code application.properties} (or an environment/profile override).
 *
 * <p>The cron expression can be overridden via
 * {@code projections.snapshot.scheduler.cron} (default: every day at 01:00).
 */
@Component
@ConditionalOnProperty(
        name         = "projections.snapshot.scheduler.enabled",
        havingValue  = "true",
        matchIfMissing = false
)
@RequiredArgsConstructor
@Slf4j
public class DailySnapshotScheduler {

    private final LedgerSnapshotService ledgerSnapshotService;

    /**
     * Run the daily ledger snapshot.
     *
     * <p>Cron default: {@code 0 0 1 * * *} — every day at 01:00 server time.
     * Override via {@code projections.snapshot.scheduler.cron}.
     */
    @Scheduled(cron = "${projections.snapshot.scheduler.cron:0 0 1 * * *}")
    public void runDailySnapshot() {
        LocalDate today = LocalDate.now();
        log.info("DailySnapshotScheduler: starting ledger balance snapshot for {}", today);
        try {
            int count = ledgerSnapshotService.generateSnapshotForDate(today).size();
            log.info("DailySnapshotScheduler: snapshot complete — {} account(s) captured for {}", count, today);
        } catch (Exception ex) {
            log.error("DailySnapshotScheduler: snapshot failed for {}", today, ex);
        }
    }
}
