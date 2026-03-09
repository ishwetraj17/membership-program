package com.firstclub.recon.entity;

/** Category of a reconciliation discrepancy. */
public enum MismatchType {
    // Layer 1 – invoice vs payment (original)
    INVOICE_NO_PAYMENT,
    PAYMENT_NO_INVOICE,
    AMOUNT_MISMATCH,
    DUPLICATE_GATEWAY_TXN,
    // Layer 2 – payment vs ledger
    PAYMENT_LEDGER_VARIANCE,
    // Layer 3 – ledger vs settlement batch
    LEDGER_BATCH_VARIANCE,
    // Layer 4 – settlement batch vs external statement
    BATCH_STATEMENT_VARIANCE,
    // Phase 14 – gateway taxonomy
    /** Gateway confirmed a payment but no local invoice record exists. Critical. */
    ORPHAN_GATEWAY_PAYMENT,
    /** More than one settlement batch exists for the same merchant+date. Critical. */
    DUPLICATE_SETTLEMENT
}
