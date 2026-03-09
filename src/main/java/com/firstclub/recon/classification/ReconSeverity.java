package com.firstclub.recon.classification;

/**
 * Operational severity of a reconciliation mismatch.
 *
 * <p>Severity drives alerting thresholds and SLA obligations:
 * <ul>
 *   <li>{@link #INFO}     — informational; reviewed during daily ops review.</li>
 *   <li>{@link #WARNING}  — requires investigation within the same business day.</li>
 *   <li>{@link #CRITICAL} — pager-duty alert; must be investigated within 2 h.</li>
 * </ul>
 */
public enum ReconSeverity {

    /** Low-impact; captured for audit trail only. */
    INFO,

    /** Requires attention within the business day but is not immediately revenue-impacting. */
    WARNING,

    /**
     * Revenue-impacting or data-integrity issue.  Examples: orphaned gateway
     * payment with funds received but no local record, duplicate settlement batch.
     * Triggers immediate alert.
     */
    CRITICAL,
}
