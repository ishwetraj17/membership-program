package com.firstclub.platform.dedup;

/**
 * Result of a deduplication check.
 *
 * <p>Consumers should treat {@link #DUPLICATE} as a signal to skip applying
 * the business effect and return a safe idempotent outcome.  {@link #NEW}
 * means the effect has not been seen before and should proceed.
 */
public enum DedupResult {

    /**
     * This combination of effect_type + fingerprint has not been seen before.
     * The caller must apply the business effect and then record it via
     * {@link BusinessEffectDedupService#recordEffect}.
     */
    NEW,

    /**
     * This combination of effect_type + fingerprint was already applied.
     * The caller must NOT apply the business effect again.
     */
    DUPLICATE
}
