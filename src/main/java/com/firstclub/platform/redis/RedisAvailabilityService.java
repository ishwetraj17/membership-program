package com.firstclub.platform.redis;

/**
 * Exposes the operational status of the Redis connection to the rest of the platform.
 *
 * <h3>Two implementations are provided</h3>
 * <ul>
 *   <li>{@code RedisAvailabilityServiceImpl} — active when {@code app.redis.enabled=true};
 *       issues a real PING to Redis and measures latency.</li>
 *   <li>{@code DisabledRedisAvailabilityService} — active by default
 *       ({@code app.redis.enabled=false}); always returns {@link RedisStatus#DISABLED}
 *       without touching the network.</li>
 * </ul>
 *
 * <h3>Usage contract for callers</h3>
 * <pre>{@code
 * if (!redisAvailabilityService.isAvailable()) {
 *     // Fall back to DB — always safe, never an error in normal operations
 *     return repository.findById(id);
 * }
 * // Fast path through Redis cache
 * }</pre>
 *
 * <p><b>Redis must never be the source of truth for financial state.</b>
 * Even when {@link #isAvailable()} returns {@code true}, callers that
 * touch ledger, payment, or subscription data must have a working DB fallback.
 */
public interface RedisAvailabilityService {

    /**
     * Returns {@code true} only when Redis is actively reachable.
     * Returns {@code false} for {@link RedisStatus#DOWN}, {@link RedisStatus#DEGRADED},
     * and {@link RedisStatus#DISABLED}.
     */
    boolean isAvailable();

    /**
     * Detailed status of the Redis connection at the time of the call.
     *
     * <p>Implementations may cache this result for a brief window (e.g. 500 ms)
     * to avoid hammering Redis on every request.
     */
    RedisStatus getStatus();

    /**
     * Latency of the most recent PING in milliseconds.
     * Returns {@code -1} when Redis is disabled or unreachable.
     */
    long getPingLatencyMs();

    /**
     * Redis server host as configured.
     * Returns {@code "disabled"} when {@code app.redis.enabled=false}.
     */
    String getHost();

    /**
     * Redis server port as configured.
     * Returns {@code 0} when {@code app.redis.enabled=false}.
     */
    int getPort();
}
