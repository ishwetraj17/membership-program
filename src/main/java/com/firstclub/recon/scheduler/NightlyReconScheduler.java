package com.firstclub.recon.scheduler;

import com.firstclub.recon.service.ReconciliationService;
import com.firstclub.recon.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Nightly jobs that run after business-day close.
 *
 * <ul>
 *   <li>02:00 — settlement sweep: PG_CLEARING → BANK for yesterday</li>
 *   <li>02:10 — reconciliation report for yesterday</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NightlyReconScheduler {

    private final SettlementService      settlementService;
    private final ReconciliationService  reconciliationService;

    /** Run settlement sweep at 02:00 daily for the prior business day. */
    @Scheduled(cron = "0 0 2 * * *")
    public void runSettlement() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("Nightly settlement starting for {}", yesterday);
        try {
            settlementService.settleForDate(yesterday);
            log.info("Nightly settlement complete for {}", yesterday);
        } catch (Exception ex) {
            log.error("Nightly settlement failed for {}", yesterday, ex);
        }
    }

    /** Run reconciliation at 02:10 daily for the prior business day. */
    @Scheduled(cron = "0 10 2 * * *")
    public void runReconciliation() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("Nightly reconciliation starting for {}", yesterday);
        try {
            reconciliationService.runForDate(yesterday);
            log.info("Nightly reconciliation complete for {}", yesterday);
        } catch (Exception ex) {
            log.error("Nightly reconciliation failed for {}", yesterday, ex);
        }
    }
}
