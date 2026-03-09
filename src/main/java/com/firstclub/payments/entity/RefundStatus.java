package com.firstclub.payments.entity;

/** Status of a refund issued against a captured payment. */
public enum RefundStatus {
    /** Money is being returned to the customer. */
    PENDING,
    /** Refund has been fully processed. */
    COMPLETED,
    /** Refund processing failed. */
    FAILED
}
