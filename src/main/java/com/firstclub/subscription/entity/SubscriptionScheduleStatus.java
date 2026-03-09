package com.firstclub.subscription.entity;

/**
 * Processing state of a {@link SubscriptionSchedule} entry.
 */
public enum SubscriptionScheduleStatus {

    /** Pending execution; effective_at has not yet been reached. */
    SCHEDULED,

    /** Successfully applied to the subscription. */
    EXECUTED,

    /** Manually cancelled before execution. */
    CANCELLED,

    /** Attempted but failed (e.g. incompatible state, payment error). */
    FAILED
}
