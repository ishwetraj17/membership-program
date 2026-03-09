package com.firstclub.payments.entity;

/**
 * Lifecycle state for a single {@link PaymentAttempt}.
 *
 * <h3>Terminal states</h3>
 * Once an attempt reaches a terminal state it cannot be mutated.
 * Terminal states: {@code CAPTURED}, {@code SUCCEEDED}, {@code FAILED},
 * {@code TIMEOUT}, {@code RECONCILED}, {@code CANCELLED}.
 *
 * <h3>Phase 8 additions</h3>
 * {@code INITIATED} — attempt object created; idempotency key assigned before dispatch.<br>
 * {@code SUCCEEDED} — terminal success (Phase 8 replacement for CAPTURED in new flows).<br>
 * {@code UNKNOWN}   — gateway did not respond; async status check pending.<br>
 * {@code RECONCILED} — UNKNOWN resolved via async gateway status check.<br>
 * {@code CANCELLED} — attempt cancelled before completion.
 */
public enum PaymentAttemptStatus {
    /** Attempt object created; gateway idempotency key assigned. */
    INITIATED,
    /** Request dispatched to gateway (legacy entry point; new flows use INITIATED). */
    STARTED,
    /** Gateway authorised the payment; capture pending (MANUAL capture mode). */
    AUTHORIZED,
    /** Payment captured successfully (legacy terminal success). */
    CAPTURED,
    /** Phase 8 terminal success — gateway confirmed payment processed. */
    SUCCEEDED,
    /** Gateway returned an explicit failure response (terminal failure). */
    FAILED,
    /** Gateway did not respond within the deadline (legacy; treated as UNKNOWN). */
    TIMEOUT,
    /**
     * Gateway call timed out or returned an ambiguous response; outcome unknown.
     * This state is <strong>not</strong> terminal — an async reconciliation job
     * will resolve it to {@code SUCCEEDED}, {@code FAILED}, or {@code RECONCILED}.
     */
    UNKNOWN,
    /** UNKNOWN attempt resolved via async gateway status check. */
    RECONCILED,
    /** Gateway requires additional customer action (e.g. 3-D Secure). */
    REQUIRES_ACTION,
    /** Attempt cancelled before dispatch. */
    CANCELLED;

    /**
     * Returns {@code true} if the attempt is in a terminal state and cannot
     * be updated further.
     */
    public boolean isTerminal() {
        return this == CAPTURED || this == SUCCEEDED || this == FAILED
                || this == TIMEOUT || this == RECONCILED || this == CANCELLED;
    }

    /**
     * Returns {@code true} if the attempt is in {@code UNKNOWN} state and
     * eligible for async gateway status reconciliation.
     */
    public boolean isResolvable() {
        return this == UNKNOWN;
    }
}
