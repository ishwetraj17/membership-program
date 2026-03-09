package com.firstclub.payments.entity;

/**
 * Lifecycle states for a {@link PaymentIntentV2}.
 *
 * <p>Allowed transitions:
 * <pre>
 *   REQUIRES_PAYMENT_METHOD → REQUIRES_CONFIRMATION, CANCELLED
 *   REQUIRES_CONFIRMATION   → PROCESSING, CANCELLED
 *   PROCESSING              → SUCCEEDED, REQUIRES_ACTION, FAILED
 *   REQUIRES_ACTION         → PROCESSING, FAILED, CANCELLED
 *   SUCCEEDED               → (terminal)
 *   FAILED                  → (terminal — may allow new PI for retry flow)
 *   CANCELLED               → (terminal)
 * </pre>
 */
public enum PaymentIntentStatusV2 {
    /** No payment method attached yet. */
    REQUIRES_PAYMENT_METHOD,
    /** Payment method attached; awaiting explicit confirmation. */
    REQUIRES_CONFIRMATION,
    /** Dispatched to gateway; awaiting result. */
    PROCESSING,
    /** Gateway requires additional customer action (e.g. 3DS challenge). */
    REQUIRES_ACTION,
    /** Payment completed successfully — terminal. */
    SUCCEEDED,
    /** Payment failed — terminal. */
    FAILED,
    /** Payment intent cancelled by merchant or customer — terminal. */
    CANCELLED;

    /** Returns true if this is a terminal state that blocks further transitions. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /** Returns true if a confirm operation is valid from this state. */
    public boolean allowsConfirm() {
        return this == REQUIRES_CONFIRMATION || this == REQUIRES_PAYMENT_METHOD;
    }

    /** Returns true if a cancel operation is valid from this state. */
    public boolean allowsCancel() {
        return this == REQUIRES_PAYMENT_METHOD || this == REQUIRES_CONFIRMATION
                || this == REQUIRES_ACTION;
    }
}
