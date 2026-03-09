package com.firstclub.platform.ops.dto;

/**
 * Admin view of a single rate-limit policy.
 *
 * <p>Returned by {@code GET /api/v2/admin/system/rate-limits} to let operators
 * inspect the effective configuration (after config overrides are applied).
 *
 * @param name             {@link com.firstclub.platform.ratelimit.RateLimitPolicy} enum name
 * @param keySegment       Redis key segment prefix used for this policy
 * @param limit            Maximum allowed requests within the window
 * @param windowSeconds    Sliding window size in seconds
 * @param keyPatternExample Illustrative Redis key pattern
 */
public record RateLimitPolicyDTO(
        String name,
        String keySegment,
        int    limit,
        long   windowSeconds,
        String keyPatternExample
) {}
