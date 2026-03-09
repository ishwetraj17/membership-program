package com.firstclub.billing.entity;

/**
 * Classification of an invoice line item.
 */
public enum InvoiceLineType {
    /** Base charge for a subscription plan period. */
    PLAN_CHARGE,

    /** Credit or debit arising from pro-rating a mid-cycle plan change.
     *  Negative amounts represent credits. */
    PRORATION,

    /** Tax applied to chargeable amounts. */
    TAX,

    /** Promotional or loyalty discount. */
    DISCOUNT,

    /** Credit balance applied from a credit note (always negative). */
    CREDIT_APPLIED,

    // ── Phase 9: India GST tax line types ────────────────────────────────────

    /** Central GST — intra-state supply, charged at half the applicable rate. */
    CGST,

    /** State GST — intra-state supply, charged at half the applicable rate. */
    SGST,

    /** Integrated GST — inter-state supply, charged at the full applicable rate. */
    IGST
}
