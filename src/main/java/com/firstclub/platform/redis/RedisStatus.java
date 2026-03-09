package com.firstclub.platform.redis;

/**
 * Represents the current operational status of the Redis connection.
 *
 * <ul>
 *   <li>{@link #UP}       — Redis is reachable and responding to PING within the command timeout.</li>
 *   <li>{@link #DEGRADED} — Redis responded but with unexpected output or high latency.</li>
 *   <li>{@link #DOWN}     — Redis is unreachable or threw an exception on PING.</li>
 *   <li>{@link #DISABLED} — {@code app.redis.enabled=false}; no connection is attempted.</li>
 * </ul>
 *
 * <p>Note: {@code DISABLED} is the default state and does not represent a failure.
 * It means the Redis stack was intentionally not activated.  All callers must
 * treat DISABLED identically to DOWN and fall back to their PostgreSQL source of truth.
 */
public enum RedisStatus {
    UP,
    DEGRADED,
    DOWN,
    DISABLED
}
