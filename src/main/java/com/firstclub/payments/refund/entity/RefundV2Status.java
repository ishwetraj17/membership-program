package com.firstclub.payments.refund.entity;

/** Lifecycle states for a refund_v2 record. */
public enum RefundV2Status {
    /** Refund request accepted; awaiting gateway or accounting confirmation. */
    PENDING,
    /** Refund has been fully processed and ledger entry posted. */
    COMPLETED,
    /** Refund processing failed; the payment's refunded_amount was not incremented. */
    FAILED
}
