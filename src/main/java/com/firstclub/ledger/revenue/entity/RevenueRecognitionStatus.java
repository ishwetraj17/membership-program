package com.firstclub.ledger.revenue.entity;

/** Lifecycle state of a single revenue recognition schedule row. */
public enum RevenueRecognitionStatus {
    /** Scheduled for future or present recognition; not yet posted to ledger. */
    PENDING,

    /** Ledger entry successfully posted; revenue moved from liability to income. */
    POSTED,

    /** Posting attempted but ledger entry failed; eligible for manual review/retry. */
    FAILED,

    /**
     * Posting was blocked by {@link com.firstclub.ledger.revenue.guard.RevenueRecognitionGuard}
     * due to the subscription or invoice being in a non-billable terminal state
     * ({@link com.firstclub.ledger.revenue.guard.GuardDecision#BLOCK} or
     * {@link com.firstclub.ledger.revenue.guard.GuardDecision#HALT}).
     * The row will not be retried automatically — requires operator action.
     */
    SKIPPED
}
