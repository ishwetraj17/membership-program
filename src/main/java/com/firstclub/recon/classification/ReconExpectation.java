package com.firstclub.recon.classification;

/**
 * Classifies a {@link com.firstclub.recon.entity.ReconMismatch} as either
 * <em>expected</em> (a known operational pattern that does not require immediate
 * action) or <em>unexpected</em> (a defect that must be investigated and resolved).
 *
 * <h3>Decision tree</h3>
 * <pre>
 *  Is the mismatch near a day boundary (timing window)?
 *    YES → EXPECTED_TIMING_DIFFERENCE
 *
 *  Is the gateway risk-hold flag set on the linked payment attempt?
 *    YES → EXPECTED_RISK_HOLD
 *
 *  Is there an active dispute on the linked invoice?
 *    YES → EXPECTED_DISPUTE_HOLD
 *
 *  Is the gateway txn ID missing from local records?
 *    YES → UNEXPECTED_GATEWAY_ERROR
 *
 *  Otherwise → UNEXPECTED_SYSTEM_ERROR
 * </pre>
 */
public enum ReconExpectation {

    /**
     * The mismatch falls within the configurable reconciliation timing window
     * around a day boundary.  Payments captured a few minutes after midnight
     * (UTC or local) may appear to be unmatched for the previous day even though
     * they legitimately belong to it.  These resolve themselves on the next run.
     */
    EXPECTED_TIMING_DIFFERENCE,

    /**
     * The gateway placed a risk hold on the payment.  Settlement is delayed but
     * not lost.  Operator should verify with the gateway dashboard and wait for
     * the hold to lift; typically 24–72 h.
     */
    EXPECTED_RISK_HOLD,

    /**
     * A customer dispute (chargeback) is in progress.  The payment is held by
     * the gateway pending resolution.  No action required until the dispute is
     * closed; track in the dispute management module.
     */
    EXPECTED_DISPUTE_HOLD,

    /**
     * An internal system error (missing ledger entry, failed DB write, event
     * processing failure) is the likely cause.  Requires developer investigation.
     */
    UNEXPECTED_SYSTEM_ERROR,

    /**
     * The gateway returned a transaction ID that has no corresponding local
     * payment record, or reported an error that is not explained by risk/dispute
     * holds.  Requires gateway and payment-ops investigation.
     */
    UNEXPECTED_GATEWAY_ERROR,
}
