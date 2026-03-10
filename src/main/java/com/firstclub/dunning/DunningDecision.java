package com.firstclub.dunning;

/**
 * Decision taken by {@code DunningStrategyService} after classifying a payment failure.
 *
 * <p>The strategy engine evaluates the {@link com.firstclub.dunning.classification.FailureCategory},
 * whether a backup payment method is available, and the remaining attempt count, then
 * emits exactly one of these decisions.  {@code DunningServiceV2Impl} acts on the
 * decision to either let the queue continue, initiate a backup charge, or terminate
 * the dunning run early.
 */
public enum DunningDecision {

    /**
     * Continue with the existing retry queue using the same payment method.
     * Applies to retryable categories when scheduled attempts remain.
     */
    RETRY,

    /**
     * Immediately schedule a retry using the backup payment method.
     * Applies when the failure suggests the primary instrument is structurally barred
     * (e.g. expired card) and a backup method is configured and available.
     */
    RETRY_WITH_BACKUP,

    /**
     * Cancel all remaining scheduled attempts and apply terminal subscription status.
     * Applies to non-retryable failure codes (stolen/lost card, fraud, do-not-honour).
     * The {@code stopped_early} flag is set on the attempt that triggered this decision.
     */
    STOP,

    /**
     * All attempts in the dunning queue have been exhausted.
     * The strategy engine returns this when no scheduled attempts remain after a
     * retryable failure; the terminal status from the policy is then applied.
     */
    EXHAUSTED
}
