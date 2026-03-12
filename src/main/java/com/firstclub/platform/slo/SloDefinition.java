package com.firstclub.platform.slo;

/**
 * Defines a single Service Level Objective (SLO) for a measurable platform guarantee.
 *
 * @param sloId         unique identifier (e.g. {@code "payment.success.rate"})
 * @param name          human-readable name
 * @param description   what this SLO measures
 * @param targetPercent target threshold as a percentage (e.g. 95.0 means ≥ 95%)
 * @param atRiskPercent threshold below which the SLO is AT_RISK but not yet BREACHED
 *                      (e.g. 92.0 means warn at 92%, breach at below targetPercent)
 * @param indicator     logical indicator name linking to a Micrometer counter or timer
 * @param window        description of the measurement window (informational, e.g. "rolling 30 days")
 */
public record SloDefinition(
        String sloId,
        String name,
        String description,
        double targetPercent,
        double atRiskPercent,
        String indicator,
        String window
) {
    /**
     * Canonical SLO definitions for the FirstClub membership platform.
     *
     * <p>These represent the contractual guarantees the platform makes about
     * core payment and billing flows.
     */
    public static final SloDefinition PAYMENT_SUCCESS_RATE = new SloDefinition(
            "payment.success.rate",
            "Payment Capture Success Rate",
            "Fraction of payment attempts that reach SUCCEEDED or CAPTURED status",
            95.0,
            90.0,
            "payment.success.total / (payment.success.total + payment.failure.total)",
            "since last restart"
    );

    public static final SloDefinition REFUND_COMPLETION_RATE = new SloDefinition(
            "refund.completion.rate",
            "Refund Completion Rate",
            "Fraction of refund attempts that complete successfully",
            99.5,
            97.0,
            "refund.completed.total / (refund.completed.total + refund.failed.total)",
            "since last restart"
    );

    public static final SloDefinition DUNNING_RECOVERY_RATE = new SloDefinition(
            "dunning.recovery.rate",
            "Dunning Recovery Rate",
            "Fraction of dunning sequences that recover a payment before exhaustion",
            60.0,
            50.0,
            "dunning.success.total / (dunning.success.total + dunning.exhausted.total)",
            "since last restart"
    );

    public static final SloDefinition DLQ_CEILING = new SloDefinition(
            "outbox.dlq.ceiling",
            "Dead-Letter Queue Depth",
            "Total DLQ entries must stay below the operational ceiling",
            100.0, // > 50 = AT_RISK, > 100 = BREACHED (used differently — see SloStatusService)
            50.0,
            "outbox.dlq.total",
            "current snapshot"
    );

    public static final SloDefinition PAYMENT_CAPTURE_P95 = new SloDefinition(
            "payment.capture.p95",
            "Payment Capture Latency P95",
            "95th-percentile end-to-end payment capture latency must stay below 3 000 ms",
            3000.0,  // target = max allowed mean ms
            5000.0,  // at-risk if mean exceeds 3s (re-used as degraded upper bound)
            "payment.capture.latency",
            "since last restart"
    );

    public static final SloDefinition WEBHOOK_DELIVERY_P95 = new SloDefinition(
            "webhook.delivery.p95",
            "Webhook Delivery Latency P95",
            "95th-percentile webhook delivery latency must stay below 5 000 ms",
            5000.0,
            8000.0,
            "webhook.delivery.latency",
            "since last restart"
    );
}
