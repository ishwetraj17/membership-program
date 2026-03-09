package com.firstclub.platform.dedup;

/**
 * Canonical string constants for business-effect types used in deduplication.
 *
 * <p>Each constant identifies a single, financially consequential side-effect
 * that must occur <em>at most once</em> for a given business entity.  The
 * value is stored in the {@code effect_type} column of
 * {@code business_effect_fingerprints} and used as a Redis key segment.
 *
 * <p>Naming convention: {@code NOUN_VERB_OUTCOME}, all uppercase.
 */
public final class BusinessEffectType {

    /** A payment attempt was confirmed as captured by the gateway. */
    public static final String PAYMENT_CAPTURE_SUCCESS       = "PAYMENT_CAPTURE_SUCCESS";

    /** A refund request was issued to the gateway and the money-movement ledger entry posted. */
    public static final String REFUND_COMPLETED              = "REFUND_COMPLETED";

    /** A dispute was opened for a payment, reserving funds. */
    public static final String DISPUTE_OPENED                = "DISPUTE_OPENED";

    /** A settlement batch was created and the bank-sweep ledger entries posted. */
    public static final String SETTLEMENT_BATCH_CREATED      = "SETTLEMENT_BATCH_CREATED";

    /** A single revenue recognition schedule row was posted to the ledger. */
    public static final String REVENUE_RECOGNITION_POSTED    = "REVENUE_RECOGNITION_POSTED";

    private BusinessEffectType() { /* constants-only utility */ }
}
