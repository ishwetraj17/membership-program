package com.firstclub.payments.entity;

/**
 * Lifecycle states of a captured payment, including partial-refund and dispute tracking.
 */
public enum PaymentStatus {
    /** Payment has been successfully charged at the gateway. */
    CAPTURED,
    /** One or more partial refunds have been issued; further refunds are still possible. */
    PARTIALLY_REFUNDED,
    /** The full captured amount has been refunded; no further refunds possible. */
    REFUNDED,
    /** A chargeback / dispute has been raised against this payment. */
    DISPUTED,
    /** Payment charge failed at the gateway. */
    FAILED
}
