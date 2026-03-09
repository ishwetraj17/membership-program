package com.firstclub.platform.ops.dto;

import java.time.LocalDateTime;

/**
 * Response DTO for the Redis health admin endpoint.
 *
 * <p>Returned by {@code GET /api/v2/admin/system/redis/health}.
 *
 * <p>Status values:
 * <ul>
 *   <li>{@code UP}       — Redis is reachable and responding normally.</li>
 *   <li>{@code DEGRADED} — Redis responded but with unexpected data or high latency.</li>
 *   <li>{@code DOWN}     — Redis is unreachable or threw an error on PING.</li>
 *   <li>{@code DISABLED} — {@code app.redis.enabled=false}; no connection attempted.</li>
 * </ul>
 *
 * @param status      current Redis status string
 * @param latencyMs   PING round-trip in milliseconds; {@code -1} when unavailable/disabled
 * @param host        Redis server hostname; {@code "disabled"} when Redis is off
 * @param port        Redis server port; {@code 0} when Redis is off
 * @param message     human-readable description of the current state
 * @param checkedAt   timestamp when this check was performed
 */
public record RedisHealthStatusDTO(
        String        status,
        long          latencyMs,
        String        host,
        int           port,
        String        message,
        LocalDateTime checkedAt
) {}
