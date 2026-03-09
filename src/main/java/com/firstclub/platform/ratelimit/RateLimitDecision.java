package com.firstclub.platform.ratelimit;

import java.time.Instant;

/**
 * Immutable result of a single rate limit check.
 *
 * <p>Use the factory methods {@link #permit} and {@link #deny} to construct
 * instances.  The {@code remaining} field is always non-negative: it is capped
 * at 0 when the request is denied.
 */
public record RateLimitDecision(
        boolean      allowed,
        RateLimitPolicy policy,
        String       key,
        int          limit,
        int          remaining,
        Instant      resetAt
) {
    /** Compact accessor for header-level code. */
    public long resetEpochSeconds() {
        return resetAt.getEpochSecond();
    }

    public static RateLimitDecision permit(
            RateLimitPolicy policy, String key, int limit, int remaining, Instant resetAt) {
        return new RateLimitDecision(true, policy, key, limit,
                Math.max(0, remaining), resetAt);
    }

    public static RateLimitDecision deny(
            RateLimitPolicy policy, String key, int limit, Instant resetAt) {
        return new RateLimitDecision(false, policy, key, limit, 0, resetAt);
    }

    /**
     * Permit decision used when Redis is unavailable.
     * Remaining = limit - 1 (as if this is the first request).
     */
    public static RateLimitDecision permissive(RateLimitPolicy policy, String key) {
        int limit = policy.getDefaultLimit();
        return permit(policy, key, limit, limit - 1,
                Instant.now().plusMillis(policy.getDefaultWindowMs()));
    }
}
