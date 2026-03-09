package com.firstclub.risk.entity;

public enum ReviewCaseStatus {
    /** Newly created, awaiting assignment or triage. */
    OPEN,
    /** Reviewer approved the payment — case is closed favourably. */
    APPROVED,
    /** Reviewer rejected the payment — case is closed unfavourably. */
    REJECTED,
    /** Escalated to a higher-tier team for further review. */
    ESCALATED,
    /** Administratively closed without a payment decision. */
    CLOSED
}
