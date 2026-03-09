package com.firstclub.payments.model;

/**
 * Lifecycle states for a PaymentIntent.
 *
 * Allowed transitions:
 *   REQUIRES_PAYMENT_METHOD → REQUIRES_CONFIRMATION, FAILED
 *   REQUIRES_CONFIRMATION   → PROCESSING, REQUIRES_ACTION, FAILED
 *   REQUIRES_ACTION         → PROCESSING, FAILED
 *   PROCESSING              → SUCCEEDED, REQUIRES_ACTION, FAILED
 *   SUCCEEDED               → (terminal)
 *   FAILED                  → REQUIRES_PAYMENT_METHOD (retry)
 */
public enum PaymentIntentStatus {
    /** A payment method must be attached before the intent can be confirmed. */
    REQUIRES_PAYMENT_METHOD,

    /** Payment method is attached; the intent must be explicitly confirmed. */
    REQUIRES_CONFIRMATION,

    /** The payment is being processed by the payment provider. */
    PROCESSING,

    /** Additional customer action is required (e.g. 3-D Secure challenge). */
    REQUIRES_ACTION,

    /** Payment completed successfully. */
    SUCCEEDED,

    /** Payment failed; the intent may be retried with a new payment method. */
    FAILED
}
