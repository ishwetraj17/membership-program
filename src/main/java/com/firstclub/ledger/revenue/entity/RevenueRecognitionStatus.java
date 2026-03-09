package com.firstclub.ledger.revenue.entity;

/** Lifecycle state of a single revenue recognition schedule row. */
public enum RevenueRecognitionStatus {
    /** Scheduled for future or present recognition; not yet posted to ledger. */
    PENDING,

    /** Ledger entry successfully posted; revenue moved from liability to income. */
    POSTED,

    /** Posting attempted but ledger entry failed; eligible for manual review/retry. */
    FAILED
}
