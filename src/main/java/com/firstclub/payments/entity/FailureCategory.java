package com.firstclub.payments.entity;

/**
 * High-level category of a payment attempt failure.
 * Used for retry decisioning and observability dashboards.
 */
public enum FailureCategory {
    /** Network or connectivity error — typically transient. */
    NETWORK,
    /** Card issuer declined the transaction. */
    ISSUER_DECLINE,
    /** Blocked by risk or fraud engine. */
    RISK_BLOCK,
    /** Internal gateway error — may be transient. */
    GATEWAY_ERROR,
    /** Customer abandoned or explicitly aborted. */
    CUSTOMER_ABORT,
    /** Category could not be determined. */
    UNKNOWN;

    /** Returns true if an attempt with this failure category is a candidate for retry. */
    public boolean isTypicallyRetriable() {
        return this == NETWORK || this == GATEWAY_ERROR;
    }
}
