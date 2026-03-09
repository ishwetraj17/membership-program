package com.firstclub.payments.disputes.entity;

/**
 * Lifecycle states for a dispute record.
 *
 * <pre>
 *   OPEN ──► UNDER_REVIEW ──► WON   (reserve released, payment restored)
 *                         └──► LOST  (chargeback posted, payment stays DISPUTED)
 *   Either terminal (WON/LOST) may be moved to CLOSED for bookkeeping.
 * </pre>
 */
public enum DisputeStatus {
    /** Dispute has been filed and is awaiting review. */
    OPEN,
    /** Dispute is actively being reviewed; evidence submission still allowed before due_by. */
    UNDER_REVIEW,
    /** Merchant won the dispute — funds returned from reserve. */
    WON,
    /** Merchant lost the dispute — chargeback expense posted. */
    LOST,
    /** Final bookkeeping state, can follow WON or LOST. */
    CLOSED
}
