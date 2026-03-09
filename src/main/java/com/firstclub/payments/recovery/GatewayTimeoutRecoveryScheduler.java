package com.firstclub.payments.recovery;

import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentAttemptStatus;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled recovery job that re-queries gateway status for payment attempts
 * stuck in the {@code UNKNOWN} state.
 *
 * <h3>Trigger condition</h3>
 * An attempt enters {@code UNKNOWN} when the gateway call timed out (or the
 * network was cut) before a definitive success/failure response was received.
 * This scheduler scans for attempts whose {@code started_at} is older than a
 * configurable threshold and delegates resolution to {@link PaymentOutcomeReconciler}.
 *
 * <h3>Singleton execution</h3>
 * Uses {@link SchedulerLockService} (advisory lock) and {@link PrimaryOnlySchedulerGuard}
 * to ensure only one application node runs the batch at a time.
 *
 * <h3>Configuration</h3>
 * <pre>
 *   payments.gateway.recovery.interval=PT5M          # How often the scheduler fires
 *   payments.gateway.recovery.stale-threshold-minutes=2  # Min age to be eligible
 * </pre>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GatewayTimeoutRecoveryScheduler {

    private static final String SCHEDULER_NAME = "gateway-timeout-recovery";

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentOutcomeReconciler paymentOutcomeReconciler;
    private final SchedulerLockService schedulerLockService;
    private final PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    @Value("${payments.gateway.recovery.stale-threshold-minutes:2}")
    private int staleThresholdMinutes;

    /**
     * Runs every {@code payments.gateway.recovery.interval} (default 5 minutes).
     *
     * <p>For each stale UNKNOWN attempt:
     * <ol>
     *   <li>Delegates to {@link PaymentOutcomeReconciler#reconcile} which runs
     *       in its own {@code REQUIRES_NEW} transaction.</li>
     *   <li>Logs the outcome; exceptions per attempt are swallowed so one bad
     *       attempt does not abort the whole batch.</li>
     * </ol>
     */
    @Scheduled(fixedDelayString = "${payments.gateway.recovery.interval:PT5M}")
    @Transactional
    public void recoverTimeoutAttempts() {
        if (!primaryOnlySchedulerGuard.canRunScheduler(SCHEDULER_NAME)) {
            return;
        }
        if (!schedulerLockService.tryAcquireForBatch(SCHEDULER_NAME)) {
            log.debug("[{}] advisory lock not acquired — another node is running this batch", SCHEDULER_NAME);
            return;
        }

        LocalDateTime threshold = LocalDateTime.now().minusMinutes(staleThresholdMinutes);
        List<PaymentAttempt> staleAttempts =
                paymentAttemptRepository.findByStatusAndStartedAtBefore(
                        PaymentAttemptStatus.UNKNOWN, threshold);

        if (staleAttempts.isEmpty()) {
            log.debug("[{}] no stale UNKNOWN attempts found", SCHEDULER_NAME);
            return;
        }

        log.info("[{}] Found {} stale UNKNOWN attempt(s) to reconcile", SCHEDULER_NAME, staleAttempts.size());

        int succeeded = 0;
        int failed = 0;
        for (PaymentAttempt attempt : staleAttempts) {
            try {
                paymentOutcomeReconciler.reconcile(attempt);
                succeeded++;
            } catch (Exception ex) {
                failed++;
                log.error("[{}] Failed to reconcile attempt {}: {}",
                        SCHEDULER_NAME, attempt.getId(), ex.getMessage(), ex);
            }
        }

        log.info("[{}] Batch complete — reconciled={} errors={}", SCHEDULER_NAME, succeeded, failed);
    }
}
