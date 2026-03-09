package com.firstclub.platform.ratelimit;

/**
 * Port for rate limit checks.
 *
 * <p>The primary implementation is {@link RedisSlidingWindowRateLimiter}.
 * When Redis is unavailable, all checks return a permissive
 * {@link RateLimitDecision} so that rate limiting degrades gracefully
 * without blocking legitimate traffic.
 *
 * <p><strong>Thread safety:</strong> all implementations must be thread-safe.
 */
public interface RateLimitService {

    /**
     * Check whether the caller identified by {@code subjects} is within the
     * configured limit for {@code policy}.
     *
     * <p>If the limit is <em>not</em> exceeded, the method records the request
     * and returns a {@link RateLimitDecision} with {@code allowed=true}.
     * If the limit is exceeded, it returns {@code allowed=false} WITHOUT
     * recording an additional entry.
     *
     * @param policy   the rate limit policy to apply
     * @param subjects ordered subject identifiers used to build the Redis key
     *                 (e.g. IP address, normalised email, merchantId+customerId)
     * @return the decision — never {@code null}
     */
    RateLimitDecision checkLimit(RateLimitPolicy policy, String... subjects);

    /**
     * Returns {@code true} when the underlying Redis store is reachable and
     * rate limiting is active.  When {@code false}, all calls to
     * {@link #checkLimit} return permissive decisions.
     */
    boolean isEnabled();
}
