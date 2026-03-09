package com.firstclub.billing.model;

/**
 * Lifecycle states for an Invoice.
 *
 * Allowed transitions:
 *   DRAFT         → OPEN, VOID
 *   OPEN          → PAID, VOID, UNCOLLECTIBLE
 *   PAID          → (terminal)
 *   VOID          → (terminal)
 *   UNCOLLECTIBLE → (terminal)
 */
public enum InvoiceStatus {
    /** Invoice is being prepared and has not yet been sent to the customer. */
    DRAFT,

    /** Invoice has been finalised and sent; payment is expected. */
    OPEN,

    /** Invoice has been paid in full. */
    PAID,

    /** Invoice was cancelled before payment was collected. */
    VOID,

    /** Invoice is unlikely to be collected (e.g. customer in arrears). */
    UNCOLLECTIBLE
}
