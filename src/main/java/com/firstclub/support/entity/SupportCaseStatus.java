package com.firstclub.support.entity;

/**
 * Lifecycle status of a {@link SupportCase}.
 */
public enum SupportCaseStatus {
    /** Case opened and awaiting investigation. */
    OPEN,
    /** Actively being worked on by an ops agent. */
    IN_PROGRESS,
    /** Awaiting response or action from the merchant / customer. */
    PENDING_CUSTOMER,
    /** Root cause identified; fix applied — awaiting confirmation. */
    RESOLVED,
    /** Case fully closed; no further action expected. */
    CLOSED
}
