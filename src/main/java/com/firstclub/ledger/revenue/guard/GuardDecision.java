package com.firstclub.ledger.revenue.guard;

/**
 * Decision emitted by {@link RevenueRecognitionGuard} for a recognition schedule row.
 *
 * <h3>Decision hierarchy (highest → lowest severity)</h3>
 * <ol>
 *   <li>{@link #HALT}  — terminal; no more recognition for this invoice/subscription</li>
 *   <li>{@link #BLOCK} — hard block; leave row PENDING or mark SKIPPED</li>
 *   <li>{@link #DEFER} — soft block; re-evaluate at next run</li>
 *   <li>{@link #FLAG}  — allow posting but mark for operator review</li>
 *   <li>{@link #ALLOW} — proceed normally</li>
 * </ol>
 */
public enum GuardDecision {

    /** Proceed with recognition normally. */
    ALLOW,

    /** Hard block — subscription in a non-billable state; do not post. */
    BLOCK,

    /**
     * Soft block — condition is temporary (e.g. PAST_DUE).
     * Leave the schedule row {@code PENDING} and re-evaluate on the next run.
     */
    DEFER,

    /**
     * Allow posting but stamp the row for operator review
     * (e.g. invoice UNCOLLECTIBLE — revenue is recognized but marked uncertain).
     */
    FLAG,

    /**
     * Terminal block — subscription cancelled/expired or invoice voided.
     * No further recognition should occur for this invoice.
     */
    HALT
}
