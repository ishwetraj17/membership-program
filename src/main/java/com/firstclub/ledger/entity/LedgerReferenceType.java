package com.firstclub.ledger.entity;

/** Domain objects that a ledger entry can reference. */
public enum LedgerReferenceType {
    INVOICE,
    PAYMENT,
    REFUND,
    SUBSCRIPTION,
    SETTLEMENT_BATCH,
    /** A single {@code revenue_recognition_schedules} row posted to the ledger. */
    REVENUE_RECOGNITION_SCHEDULE,
    /** A dispute/chargeback record. */
    DISPUTE
}
