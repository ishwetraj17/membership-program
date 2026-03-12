package com.firstclub.platform.slo;

import com.firstclub.payments.repository.DeadLetterMessageRepository;
import com.firstclub.platform.metrics.FinancialMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Computes the operational status for each registered SLO.
 *
 * <h3>Data source</h3>
 * SLO values are computed from Micrometer counters and timers that
 * {@link FinancialMetrics} registers at startup.  Counters reset to
 * zero on pod restart; the response documents this in the {@code notes} field.
 *
 * <h3>Rate-based SLOs</h3>
 * A rate is computed as: {@code success / (success + failure) × 100}.
 * If both counters are zero, the status is {@code INSUFFICIENT_DATA}.
 *
 * <h3>DLQ ceiling SLO</h3>
 * Backed by a DB count (via {@link DeadLetterMessageRepository#count()}) so
 * it reflects the live state regardless of pod restarts.
 *
 * <h3>Latency SLOs</h3>
 * Evaluated against {@code timer.mean(MILLISECONDS)}.  When the count is zero
 * (no requests since restart), status is {@code INSUFFICIENT_DATA}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SloStatusService {

    private final FinancialMetrics              financialMetrics;
    private final DeadLetterMessageRepository   deadLetterRepo;

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns status for all registered SLOs. */
    public List<SloStatusEntry> getAllSloStatuses() {
        return List.of(
                evaluatePaymentSuccessRate(),
                evaluateRefundCompletionRate(),
                evaluateDunningRecoveryRate(),
                evaluateDlqCeiling(),
                evaluatePaymentCaptureLatency(),
                evaluateWebhookDeliveryLatency()
        );
    }

    /** Returns the overall SLO health: HEALTHY if all are MEETING, DEGRADED if any AT_RISK, CRITICAL if any BREACHED. */
    public String overallSloStatus() {
        List<SloStatusEntry> entries = getAllSloStatuses();
        boolean anyBreached = entries.stream().anyMatch(e -> e.status() == SloStatus.BREACHED);
        boolean anyAtRisk   = entries.stream().anyMatch(e -> e.status() == SloStatus.AT_RISK);
        if (anyBreached) return "CRITICAL";
        if (anyAtRisk)   return "AT_RISK";
        return "HEALTHY";
    }

    // ── Private evaluators ────────────────────────────────────────────────────

    private SloStatusEntry evaluatePaymentSuccessRate() {
        SloDefinition def = SloDefinition.PAYMENT_SUCCESS_RATE;
        double successes = financialMetrics.getPaymentSuccess().count();
        double failures  = financialMetrics.getPaymentFailure().count();
        double total     = successes + failures;

        if (total == 0.0) {
            return insufficient(def, "No payment attempts recorded since last restart");
        }

        double rate = (successes / total) * 100.0;
        return rateEntry(def, rate,
                String.format("%.1f%% (%d successes / %d total) since last restart",
                        rate, (long) successes, (long) total));
    }

    private SloStatusEntry evaluateRefundCompletionRate() {
        SloDefinition def = SloDefinition.REFUND_COMPLETION_RATE;
        double completed = financialMetrics.getRefundCompleted().count();
        double failed    = financialMetrics.getRefundFailed().count();
        double total     = completed + failed;

        if (total == 0.0) {
            return insufficient(def, "No refund attempts recorded since last restart");
        }

        double rate = (completed / total) * 100.0;
        return rateEntry(def, rate,
                String.format("%.1f%% (%d completed / %d total) since last restart",
                        rate, (long) completed, (long) total));
    }

    private SloStatusEntry evaluateDunningRecoveryRate() {
        SloDefinition def = SloDefinition.DUNNING_RECOVERY_RATE;
        double successes = financialMetrics.getDunningSuccess().count();
        double exhausted = financialMetrics.getDunningExhausted().count();
        double total     = successes + exhausted;

        if (total == 0.0) {
            return insufficient(def, "No dunning sequences completed since last restart");
        }

        double rate = (successes / total) * 100.0;
        return rateEntry(def, rate,
                String.format("%.1f%% (%d recovered / %d total sequences) since last restart",
                        rate, (long) successes, (long) total));
    }

    private SloStatusEntry evaluateDlqCeiling() {
        SloDefinition def = SloDefinition.DLQ_CEILING;
        long depth = deadLetterRepo.count();

        SloStatus status;
        if (depth > (long) def.targetPercent()) {
            status = SloStatus.BREACHED;
        } else if (depth > (long) def.atRiskPercent()) {
            status = SloStatus.AT_RISK;
        } else {
            status = SloStatus.MEETING;
        }

        return new SloStatusEntry(
                def.sloId(), def.name(), def.targetPercent(),
                (double) depth, status, def.window(),
                String.format("Current DLQ depth: %d. AT_RISK > %.0f, BREACHED > %.0f",
                        depth, def.atRiskPercent(), def.targetPercent()),
                LocalDateTime.now());
    }

    private SloStatusEntry evaluatePaymentCaptureLatency() {
        SloDefinition def = SloDefinition.PAYMENT_CAPTURE_P95;
        Timer timer = financialMetrics.getPaymentCaptureLatency();

        if (timer.count() == 0) {
            return insufficient(def, "No payment capture measurements recorded since last restart");
        }

        double meanMs = timer.mean(TimeUnit.MILLISECONDS);
        SloStatus status = meanMs <= def.targetPercent() ? SloStatus.MEETING
                         : meanMs <= def.atRiskPercent()  ? SloStatus.AT_RISK
                         : SloStatus.BREACHED;

        return new SloStatusEntry(
                def.sloId(), def.name(), def.targetPercent(),
                meanMs, status, def.window(),
                String.format("Mean capture latency: %.0f ms (target ≤ %.0f ms, at-risk > %.0f ms)",
                        meanMs, def.targetPercent(), def.atRiskPercent()),
                LocalDateTime.now());
    }

    private SloStatusEntry evaluateWebhookDeliveryLatency() {
        SloDefinition def = SloDefinition.WEBHOOK_DELIVERY_P95;
        Timer timer = financialMetrics.getWebhookDeliveryLatency();

        if (timer.count() == 0) {
            return insufficient(def, "No webhook delivery measurements recorded since last restart");
        }

        double meanMs = timer.mean(TimeUnit.MILLISECONDS);
        SloStatus status = meanMs <= def.targetPercent() ? SloStatus.MEETING
                         : meanMs <= def.atRiskPercent()  ? SloStatus.AT_RISK
                         : SloStatus.BREACHED;

        return new SloStatusEntry(
                def.sloId(), def.name(), def.targetPercent(),
                meanMs, status, def.window(),
                String.format("Mean delivery latency: %.0f ms (target ≤ %.0f ms, at-risk > %.0f ms)",
                        meanMs, def.targetPercent(), def.atRiskPercent()),
                LocalDateTime.now());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static SloStatusEntry insufficient(SloDefinition def, String reason) {
        return new SloStatusEntry(
                def.sloId(), def.name(), def.targetPercent(),
                null, SloStatus.INSUFFICIENT_DATA, def.window(), reason,
                LocalDateTime.now());
    }

    private static SloStatusEntry rateEntry(SloDefinition def, double rate, String notes) {
        SloStatus status = rate >= def.targetPercent() ? SloStatus.MEETING
                         : rate >= def.atRiskPercent() ? SloStatus.AT_RISK
                         : SloStatus.BREACHED;
        return new SloStatusEntry(
                def.sloId(), def.name(), def.targetPercent(),
                rate, status, def.window(), notes,
                LocalDateTime.now());
    }
}
