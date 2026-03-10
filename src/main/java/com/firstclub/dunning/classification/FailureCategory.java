package com.firstclub.dunning.classification;

/**
 * Broad category derived from a raw gateway failure code.
 *
 * <p>Used by {@link FailureCodeClassifier} to bucket any gateway-specific error
 * string into one of these categories so that {@code DunningStrategyService}
 * can apply consistent policy without understanding every raw code.
 *
 * <ul>
 *   <li><b>Retryable</b> ({@link #INSUFFICIENT_FUNDS}, {@link #CARD_DECLINED_GENERIC},
 *       {@link #GATEWAY_TIMEOUT}) — retry per policy schedule; the underlying problem
 *       may resolve itself.</li>
 *   <li><b>Retry-with-backup</b> ({@link #CARD_EXPIRED}, {@link #CARD_NOT_SUPPORTED},
 *       {@link #ISSUER_NOT_AVAILABLE}) — the primary instrument is structurally barred;
 *       only a different payment method can succeed.</li>
 *   <li><b>Non-retryable</b> ({@link #CARD_STOLEN}, {@link #CARD_LOST},
 *       {@link #FRAUDULENT}, {@link #DO_NOT_HONOR}, {@link #INVALID_ACCOUNT}) —
 *       continuing would be harmful or pointless; stop and apply terminal status.</li>
 *   <li>{@link #UNKNOWN} — code not in the classifier's map; treated as
 *       {@code CARD_DECLINED_GENERIC} by the strategy engine.</li>
 * </ul>
 */
public enum FailureCategory {

    // ── Retryable ─────────────────────────────────────────────────────────────

    /** Temporary lack of funds; may succeed in a later attempt. */
    INSUFFICIENT_FUNDS,

    /** Generic decline; no specific actionable information from the issuer. */
    CARD_DECLINED_GENERIC,

    /** Gateway or processor timeout; network-level transient failure. */
    GATEWAY_TIMEOUT,

    // ── Retry-with-backup ─────────────────────────────────────────────────────

    /** Card is past its expiry date; a different instrument is needed. */
    CARD_EXPIRED,

    /** Card does not support this transaction type or currency. */
    CARD_NOT_SUPPORTED,

    /** Issuer unreachable but not definitively blocking; try a different method. */
    ISSUER_NOT_AVAILABLE,

    // ── Non-retryable ─────────────────────────────────────────────────────────

    /** Card reported stolen; retrying would be a compliance violation. */
    CARD_STOLEN,

    /** Card reported lost; same as stolen for retry purposes. */
    CARD_LOST,

    /** Issuer or risk engine flagged the transaction as fraudulent. */
    FRAUDULENT,

    /** Broad "do not honour" mandate from the issuer; no retry permitted. */
    DO_NOT_HONOR,

    /** Account closed, invalid, or non-existent. */
    INVALID_ACCOUNT,

    // ── Fallback ──────────────────────────────────────────────────────────────

    /** Gateway returned no code or a code not in the classifier's map. */
    UNKNOWN
}
