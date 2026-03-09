package com.firstclub.payments.entity;

/**
 * Lifecycle status of a {@link PaymentMethodMandate}.
 */
public enum MandateStatus {

    /** Mandate has been submitted to the bank/gateway but not yet confirmed. */
    PENDING,

    /** Mandate has been approved by the bank; auto-debit can proceed up to max_amount. */
    ACTIVE,

    /** Mandate registration failed at the bank or gateway. */
    FAILED,

    /** Mandate has been revoked; no further debits allowed under this mandate. */
    REVOKED;

    /** Returns {@code true} if this mandate can be used for auto-debit. */
    public boolean isActionable() {
        return this == ACTIVE;
    }
}
