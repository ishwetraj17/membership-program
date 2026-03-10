package com.firstclub.ledger.revenue.guard;

/**
 * Accounting policy code describing the treatment applied to a recognition row.
 *
 * <p>Stored as {@code policy_code} on {@code revenue_recognition_schedules} so
 * that finance auditors can query the exact rule that was applied on any given
 * day, even after the subscription status changes.
 */
public enum RecognitionPolicyCode {

    /**
     * Standard ASC 606 / IFRS 15 path — recognize the daily slice as earned.
     * Applied when the subscription is {@code ACTIVE} or {@code TRIALING}.
     */
    RECOGNIZE,

    /**
     * Skip this period.  The schedule row is left {@code PENDING} to be
     * re-evaluated on the next posting run.
     * Applied when subscription is {@code PAUSED}.
     */
    SKIP,

    /**
     * Revenue exists but payment has not cleared.  Defer recognition until the
     * invoice transitions out of {@code PAST_DUE} / {@code INCOMPLETE}.
     */
    DEFER_UNTIL_PAID,

    /**
     * Stop all further recognition.  Applied when the subscription is
     * {@code SUSPENDED}, {@code CANCELLED}, or {@code EXPIRED}.
     */
    HALT,

    /**
     * The invoice has been voided.  Any previously {@code POSTED} entries
     * require manual reversal.  New recognition is blocked.
     */
    REVERSE_ON_VOID
}
