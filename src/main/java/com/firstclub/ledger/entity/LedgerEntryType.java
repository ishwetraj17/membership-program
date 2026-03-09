package com.firstclub.ledger.entity;

/** Types of business events that generate a journal entry. */
public enum LedgerEntryType {
    PAYMENT_CAPTURED,
    REFUND_ISSUED,
    REVENUE_RECOGNIZED,
    SETTLEMENT,
    /** Funds frozen into the dispute reserve when a chargeback is raised. */
    DISPUTE_OPENED,
    /** Reserve released back to gateway clearing when merchant wins the dispute. */
    DISPUTE_WON,
    /** Chargeback expense posted when merchant loses the dispute. */
    CHARGEBACK_POSTED,
    /**
     * Phase 10: A correction entry that mirrors all lines of the original with
     * flipped debit/credit sides.  The {@code reversalOfEntryId} field on the
     * {@link LedgerEntry} points to the original entry being corrected.
     * The pair (original + reversal) nets to zero.
     */
    REVERSAL
}
