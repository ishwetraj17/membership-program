package com.firstclub.payments.gateway;

import com.firstclub.payments.entity.FailureCategory;

/**
 * Normalised result of a gateway call or a gateway status poll.
 *
 * <p>{@code GatewayResult} is an immutable value object produced by
 * {@link PaymentGatewayCallService#submitPayment} and
 * {@link GatewayStatusResolver#resolveStatus}.  It decouples business logic
 * from gateway-specific response structures.
 *
 * <h3>Factory methods</h3>
 * Use the static factories ({@link #succeeded}, {@link #failed}, {@link #timeout},
 * {@link #unknown}) rather than calling the canonical record constructor directly.
 *
 * <h3>Reconciliation</h3>
 * If {@link #needsReconciliation()} returns {@code true}, the calling service
 * must mark the attempt as {@code UNKNOWN} and schedule an async job to resolve it.
 *
 * @param status              normalised outcome
 * @param gatewayTransactionId gateway-assigned transaction identifier (may be null for timeouts)
 * @param gatewayReference    legacy reference token kept for audit compat (may equal txnId)
 * @param responseCode        gateway response / error code
 * @param responseMessage     human-readable gateway message
 * @param rawResponseJson     raw JSON received from the gateway (null for timeouts)
 * @param latencyMs           round-trip time in milliseconds
 * @param failureCategory     high-level failure classification (null for success/unknown)
 */
public record GatewayResult(
        GatewayResultStatus status,
        String gatewayTransactionId,
        String gatewayReference,
        String responseCode,
        String responseMessage,
        String rawResponseJson,
        Long latencyMs,
        FailureCategory failureCategory
) {

    // ── Convenience predicates ────────────────────────────────────────────────

    public boolean isSucceeded()        { return status == GatewayResultStatus.SUCCEEDED; }
    public boolean isFailed()           { return status == GatewayResultStatus.FAILED; }
    public boolean isTimeout()          { return status == GatewayResultStatus.TIMEOUT; }
    public boolean needsReconciliation(){ return status.requiresReconciliation(); }

    // ── Static factories ──────────────────────────────────────────────────────

    /**
     * Gateway confirmed the payment was processed.
     *
     * @param txnId        gateway transaction ID
     * @param responseCode gateway success code (e.g. {@code "00"}, {@code "SUCCESS"})
     * @param latencyMs    round-trip time
     */
    public static GatewayResult succeeded(String txnId, String responseCode, Long latencyMs) {
        return new GatewayResult(
                GatewayResultStatus.SUCCEEDED,
                txnId, txnId,
                responseCode, null, null,
                latencyMs, null
        );
    }

    /**
     * Gateway returned an explicit failure.
     *
     * @param category     high-level failure category for retry decisioning
     * @param message      human-readable decline reason
     * @param responseCode gateway decline code
     * @param latencyMs    round-trip time
     */
    public static GatewayResult failed(FailureCategory category, String message,
                                        String responseCode, Long latencyMs) {
        return new GatewayResult(
                GatewayResultStatus.FAILED,
                null, null,
                responseCode, message, null,
                latencyMs, category
        );
    }

    /**
     * Gateway did not respond within the request deadline.
     *
     * @param latencyMs elapsed time before the deadline was exceeded
     */
    public static GatewayResult timeout(Long latencyMs) {
        return new GatewayResult(
                GatewayResultStatus.TIMEOUT,
                null, null,
                "TIMEOUT", "Gateway did not respond within the request deadline", null,
                latencyMs, FailureCategory.NETWORK
        );
    }

    /**
     * Gateway acknowledged the request but outcome is not yet determined.
     *
     * @param txnId       partial transaction ID if returned by the gateway
     * @param rawJson     raw gateway response payload
     * @param latencyMs   round-trip time
     */
    public static GatewayResult unknown(String txnId, String rawJson, Long latencyMs) {
        return new GatewayResult(
                GatewayResultStatus.UNKNOWN,
                txnId, txnId,
                null, null, rawJson,
                latencyMs, null
        );
    }
}
