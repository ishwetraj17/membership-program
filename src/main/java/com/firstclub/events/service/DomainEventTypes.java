package com.firstclub.events.service;

/**
 * Canonical domain event type names used across the platform.
 *
 * <p>V29 adds expanded coverage: subscription lifecycle, payment funnel,
 * refund, dispute, settlement, reconciliation, and risk events.
 */
public final class DomainEventTypes {

    private DomainEventTypes() {}

    // ── Existing ──────────────────────────────────────────────────────────────
    public static final String INVOICE_CREATED        = "INVOICE_CREATED";
    public static final String PAYMENT_SUCCEEDED      = "PAYMENT_SUCCEEDED";
    public static final String SUBSCRIPTION_ACTIVATED = "SUBSCRIPTION_ACTIVATED";
    public static final String REFUND_ISSUED          = "REFUND_ISSUED";

    // ── Subscription lifecycle (V29) ──────────────────────────────────────────
    public static final String SUBSCRIPTION_CREATED   = "SUBSCRIPTION_CREATED";
    public static final String SUBSCRIPTION_CANCELLED = "SUBSCRIPTION_CANCELLED";
    public static final String SUBSCRIPTION_PAST_DUE  = "SUBSCRIPTION_PAST_DUE";
    public static final String SUBSCRIPTION_SUSPENDED = "SUBSCRIPTION_SUSPENDED";

    // ── Payment funnel (V29) ──────────────────────────────────────────────────
    public static final String PAYMENT_INTENT_CREATED   = "PAYMENT_INTENT_CREATED";
    public static final String PAYMENT_ATTEMPT_STARTED  = "PAYMENT_ATTEMPT_STARTED";
    public static final String PAYMENT_ATTEMPT_FAILED   = "PAYMENT_ATTEMPT_FAILED";

    // ── Refund and dispute (V29) ──────────────────────────────────────────────
    public static final String REFUND_COMPLETED = "REFUND_COMPLETED";
    public static final String DISPUTE_OPENED   = "DISPUTE_OPENED";

    // ── Finance ops (V29) ─────────────────────────────────────────────────────
    public static final String SETTLEMENT_COMPLETED = "SETTLEMENT_COMPLETED";
    public static final String RECON_COMPLETED      = "RECON_COMPLETED";

    // ── Risk (V29) ────────────────────────────────────────────────────────────
    public static final String RISK_DECISION_MADE = "RISK_DECISION_MADE";

    /** Immutable set of all V29 event type strings for validation/registry use. */
    public static final java.util.Set<String> ALL = java.util.Set.of(
            INVOICE_CREATED, PAYMENT_SUCCEEDED, SUBSCRIPTION_ACTIVATED, REFUND_ISSUED,
            SUBSCRIPTION_CREATED, SUBSCRIPTION_CANCELLED, SUBSCRIPTION_PAST_DUE, SUBSCRIPTION_SUSPENDED,
            PAYMENT_INTENT_CREATED, PAYMENT_ATTEMPT_STARTED, PAYMENT_ATTEMPT_FAILED,
            REFUND_COMPLETED, DISPUTE_OPENED,
            SETTLEMENT_COMPLETED, RECON_COMPLETED,
            RISK_DECISION_MADE
    );
}
