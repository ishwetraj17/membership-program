package com.firstclub.outbox.config;

/**
 * Canonical string constants for domain event types written to the outbox.
 *
 * <p>Use these constants everywhere a domain event type is referenced so that
 * publishers and handlers stay in sync without runtime string matching.
 */
public final class DomainEventTypes {

    // Existing
    public static final String INVOICE_CREATED         = "INVOICE_CREATED";
    public static final String PAYMENT_SUCCEEDED       = "PAYMENT_SUCCEEDED";
    public static final String SUBSCRIPTION_ACTIVATED  = "SUBSCRIPTION_ACTIVATED";
    public static final String REFUND_ISSUED           = "REFUND_ISSUED";

    // V29 additions
    public static final String SUBSCRIPTION_CREATED    = "SUBSCRIPTION_CREATED";
    public static final String SUBSCRIPTION_CANCELLED  = "SUBSCRIPTION_CANCELLED";
    public static final String SUBSCRIPTION_PAST_DUE   = "SUBSCRIPTION_PAST_DUE";
    public static final String SUBSCRIPTION_SUSPENDED  = "SUBSCRIPTION_SUSPENDED";
    public static final String PAYMENT_INTENT_CREATED  = "PAYMENT_INTENT_CREATED";
    public static final String PAYMENT_ATTEMPT_STARTED = "PAYMENT_ATTEMPT_STARTED";
    public static final String PAYMENT_ATTEMPT_FAILED  = "PAYMENT_ATTEMPT_FAILED";
    public static final String REFUND_COMPLETED        = "REFUND_COMPLETED";
    public static final String DISPUTE_OPENED          = "DISPUTE_OPENED";
    public static final String SETTLEMENT_COMPLETED    = "SETTLEMENT_COMPLETED";
    public static final String RECON_COMPLETED         = "RECON_COMPLETED";
    public static final String RISK_DECISION_MADE      = "RISK_DECISION_MADE";

    private DomainEventTypes() { /* constants only */ }
}
