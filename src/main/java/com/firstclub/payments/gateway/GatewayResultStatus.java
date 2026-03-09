package com.firstclub.payments.gateway;

/**
 * Normalised status returned by a gateway call or a gateway status poll.
 *
 * <p>This is a <em>higher-level</em> abstraction over raw gateway response codes.
 * Callers use this to drive payment intent state-machine transitions without
 * coupling business logic to gateway-specific response formats.
 *
 * <h3>Reconciliation</h3>
 * Any status that {@link #requiresReconciliation()} is not final: an async job
 * ({@link com.firstclub.payments.recovery.GatewayTimeoutRecoveryScheduler}) will
 * poll the gateway and attempt to resolve the attempt to a definitive state.
 */
public enum GatewayResultStatus {

    /** Gateway confirmed the payment was processed successfully. */
    SUCCEEDED,

    /** Gateway returned an explicit decline or error response. */
    FAILED,

    /**
     * Gateway acknowledged the request but outcome is not yet known
     * (e.g. processing delay, deferred capture).
     */
    UNKNOWN,

    /**
     * No response was received from the gateway before the deadline elapsed.
     * Treated identically to {@code UNKNOWN} for reconciliation purposes.
     */
    TIMEOUT;

    /**
     * Returns {@code true} if the outcome could not be determined synchronously
     * and requires an asynchronous gateway status check.
     */
    public boolean requiresReconciliation() {
        return this == UNKNOWN || this == TIMEOUT;
    }
}
