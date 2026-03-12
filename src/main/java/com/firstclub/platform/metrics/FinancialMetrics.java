package com.firstclub.platform.metrics;

import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.payments.disputes.entity.DisputeStatus;
import com.firstclub.payments.disputes.repository.DisputeRepository;
import com.firstclub.payments.repository.DeadLetterMessageRepository;
import com.firstclub.reporting.projections.service.ProjectionLagMonitor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Central registry for all financial and operational Micrometer metrics.
 *
 * <h3>Counters (monotone, reset on restart)</h3>
 * Increment these from service code when an event occurs. Exposed as
 * {@code *_total} by Prometheus.
 *
 * <h3>Timers</h3>
 * Wrap critical code paths with {@link Timer#record(Runnable)} or
 * {@link Timer.Sample#stop(Timer)} to capture latency distributions.
 *
 * <h3>Gauges (live DB reads, sampled on each scrape)</h3>
 * These query the database on every Prometheus scrape. Keep supplier
 * lambdas cheap — they run outside a transaction on the scrape thread.
 *
 * <h3>Metric names</h3>
 * <pre>
 *   payment.success.total          payment.failure.total         payment.unknown.total
 *   refund.completed.total         refund.failed.total
 *   dunning.success.total          dunning.exhausted.total
 *   dispute.opened.total
 *   ledger.invariant.violation.total
 *   outbox.dlq.total
 *   payment.capture.latency        webhook.delivery.latency
 *   recon.run.duration             outbox.handler.duration
 *   outbox.backlog                 dlq.depth
 *   open.dispute.count             past_due.subscription.count
 *   projection.lag.seconds
 * </pre>
 */
@Component
@Getter
public class FinancialMetrics {

    // ── Payment Counters ──────────────────────────────────────────────────────

    /** Increment when a payment attempt reaches SUCCEEDED / CAPTURED. */
    private final Counter paymentSuccess;

    /** Increment when a payment attempt reaches FAILED. */
    private final Counter paymentFailure;

    /** Increment when a payment attempt transitions to UNKNOWN status. */
    private final Counter paymentUnknown;

    // ── Refund Counters ───────────────────────────────────────────────────────

    /** Increment when a RefundV2 transitions to COMPLETED. */
    private final Counter refundCompleted;

    /** Increment when a RefundV2 transitions to FAILED. */
    private final Counter refundFailed;

    // ── Dunning Counters ──────────────────────────────────────────────────────

    /** Increment when a dunning sequence recovers payment (subscription re-activated). */
    private final Counter dunningSuccess;

    /** Increment when a dunning sequence reaches the maximum retry limit without recovery. */
    private final Counter dunningExhausted;

    // ── Dispute Counter ───────────────────────────────────────────────────────

    /** Increment when a new dispute is opened against a payment. */
    private final Counter disputeOpened;

    // ── Platform Integrity Counters ───────────────────────────────────────────

    /** Increment when an integrity checker detects a ledger invariant violation. */
    private final Counter ledgerInvariantViolation;

    /** Increment when an outbox event is moved to the dead-letter queue. */
    private final Counter outboxDlq;

    // ── Latency Timers ────────────────────────────────────────────────────────

    /** Records end-to-end payment capture latency including gateway round-trip. */
    private final Timer paymentCaptureLatency;

    /** Records end-to-end webhook delivery latency to the merchant endpoint. */
    private final Timer webhookDeliveryLatency;

    /** Records the wall-clock duration of a full reconciliation batch run. */
    private final Timer reconRunDuration;

    /** Records the time taken by the outbox dispatcher to process one event. */
    private final Timer outboxHandlerDuration;

    // ── Constructor: registers all instruments ────────────────────────────────

    public FinancialMetrics(MeterRegistry registry,
                            OutboxEventRepository outboxEventRepository,
                            DeadLetterMessageRepository deadLetterMessageRepository,
                            DisputeRepository disputeRepository,
                            SubscriptionRepository subscriptionRepository,
                            ProjectionLagMonitor projectionLagMonitor) {

        // Counters
        this.paymentSuccess = Counter.builder("payment.success.total")
                .description("Total number of successfully captured payment attempts")
                .register(registry);

        this.paymentFailure = Counter.builder("payment.failure.total")
                .description("Total number of failed payment attempts (gateway rejection)")
                .register(registry);

        this.paymentUnknown = Counter.builder("payment.unknown.total")
                .description("Total number of payment attempts with unknown/ambiguous gateway response")
                .register(registry);

        this.refundCompleted = Counter.builder("refund.completed.total")
                .description("Total number of successfully completed refunds")
                .register(registry);

        this.refundFailed = Counter.builder("refund.failed.total")
                .description("Total number of failed refund attempts")
                .register(registry);

        this.dunningSuccess = Counter.builder("dunning.success.total")
                .description("Total number of dunning sequences that recovered a payment")
                .register(registry);

        this.dunningExhausted = Counter.builder("dunning.exhausted.total")
                .description("Total number of dunning sequences exhausted without recovery (subscription cancelled)")
                .register(registry);

        this.disputeOpened = Counter.builder("dispute.opened.total")
                .description("Total number of disputes (chargebacks) opened against payments")
                .register(registry);

        this.ledgerInvariantViolation = Counter.builder("ledger.invariant.violation.total")
                .description("Total number of ledger double-entry invariant violations detected by integrity checks")
                .register(registry);

        this.outboxDlq = Counter.builder("outbox.dlq.total")
                .description("Total number of outbox events moved to the dead-letter queue after max retries")
                .register(registry);

        // Timers
        this.paymentCaptureLatency = Timer.builder("payment.capture.latency")
                .description("End-to-end latency for payment capture including gateway call")
                .register(registry);

        this.webhookDeliveryLatency = Timer.builder("webhook.delivery.latency")
                .description("End-to-end latency for webhook delivery attempt to merchant endpoint")
                .register(registry);

        this.reconRunDuration = Timer.builder("recon.run.duration")
                .description("Wall-clock duration of a full reconciliation batch run across all transactions")
                .register(registry);

        this.outboxHandlerDuration = Timer.builder("outbox.handler.duration")
                .description("Time taken by the outbox event dispatcher to fully process one event")
                .register(registry);

        // Gauges — sampled by Prometheus on each scrape, backed by live DB queries
        Gauge.builder("outbox.backlog", outboxEventRepository,
                        r -> (double) (r.countByStatus(OutboxEventStatus.NEW)
                                + r.countByStatus(OutboxEventStatus.PROCESSING)))
                .description("Number of outbox events currently pending or being processed")
                .register(registry);

        Gauge.builder("dlq.depth", deadLetterMessageRepository,
                        r -> (double) r.count())
                .description("Number of events currently sitting in the dead-letter queue")
                .register(registry);

        Gauge.builder("open.dispute.count", disputeRepository,
                        r -> (double) r.countByStatusIn(
                                List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW)))
                .description("Number of disputes currently open or actively under review")
                .register(registry);

        Gauge.builder("past_due.subscription.count", subscriptionRepository,
                        r -> (double) r.countBySubscriptionStatus(
                                Subscription.SubscriptionStatus.PAST_DUE))
                .description("Number of subscriptions currently in PAST_DUE status awaiting dunning resolution")
                .register(registry);

        Gauge.builder("projection.lag.seconds", projectionLagMonitor,
                        m -> (double) m.checkAllProjections().values().stream()
                                .mapToLong(report -> Math.max(0L, report.getLagSeconds()))
                                .max()
                                .orElse(0L))
                .description("Maximum projection staleness lag across all read-model projections in seconds")
                .register(registry);
    }
}
