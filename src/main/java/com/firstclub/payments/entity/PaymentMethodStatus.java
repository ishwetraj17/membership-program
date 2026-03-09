package com.firstclub.payments.entity;

/**
 * Lifecycle status of a {@link PaymentMethod}.
 */
public enum PaymentMethodStatus {

    /** Instrument is valid and can be charged. */
    ACTIVE,

    /** Temporarily disabled by the merchant or customer. */
    INACTIVE,

    /** Card or token has expired at the issuer level. */
    EXPIRED,

    /**
     * Permanently revoked — cannot be re-activated or used for any new
     * payment intent.  Once REVOKED, the record is kept for audit purposes only.
     */
    REVOKED;

    /** Returns {@code true} if this method may be used for new charge attempts. */
    public boolean isUsable() {
        return this == ACTIVE;
    }
}
