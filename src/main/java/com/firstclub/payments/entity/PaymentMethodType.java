package com.firstclub.payments.entity;

/**
 * The type of reusable payment instrument a customer has stored with the platform.
 *
 * <p>Raw card data (PAN, CVV) is <strong>never</strong> stored.  Only the
 * tokenized, provider-opaque reference is persisted.
 */
public enum PaymentMethodType {

    /** Tokenized credit/debit card (e.g. Razorpay card token). */
    CARD,

    /** UPI VPA or token reference. */
    UPI,

    /** Netbanking token issued by a payment gateway. */
    NETBANKING,

    /** Digital wallet reference (Paytm, PhonePe, etc.). */
    WALLET,

    /**
     * A pre-approved standing mandate (e.g. NACH, eMandate).
     * Always has an associated {@link PaymentMethodMandate} record.
     */
    MANDATE;

    /**
     * Returns {@code true} if this method type natively supports mandate creation.
     * CARD mandates (recurring card authorities) and MANDATE types are supported.
     */
    public boolean supportsMandates() {
        return this == MANDATE || this == CARD;
    }
}
