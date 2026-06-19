package com.firstclub.membership.security;

/**
 * Per-client rate-limiting strategy. A single token is consumed per request, keyed by the caller
 * (user or IP). Two interchangeable implementations exist:
 *
 * <ul>
 *   <li>{@link LocalRateLimiter} — in-process token buckets (Bucket4j + Caffeine). The default;
 *       correct for a single node.</li>
 *   <li>{@link RedisRateLimiter} — a Redis-backed token bucket (activated by the {@code redis}
 *       profile) so the limit is shared across all pods in a horizontally-scaled deployment.</li>
 * </ul>
 *
 * Both honour the same {@code rate-limit.capacity} / {@code rate-limit.refill-period-seconds}
 * configuration, so swapping strategies changes <em>where</em> the counter lives, never the limit.
 */
public interface RateLimiter {

    /**
     * Attempts to consume one token for {@code clientKey}.
     *
     * @return {@code true} if a token was available (request allowed), {@code false} if the
     *         client is currently over its limit (request should be rejected with HTTP 429).
     */
    boolean tryConsume(String clientKey);
}
