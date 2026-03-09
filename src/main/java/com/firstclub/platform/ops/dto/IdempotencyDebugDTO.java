package com.firstclub.platform.ops.dto;

import java.time.LocalDateTime;

/**
 * Combined view of the Redis and PostgreSQL state for a single idempotency key.
 * Returned by the ops-admin debug endpoint.
 */
public record IdempotencyDebugDTO(
        String merchantId,
        String idempotencyKey,
        /** Redis state: ABSENT | PROCESSING | CACHED */
        String redisState,
        /** DB state: ABSENT | PLACEHOLDER | PROCESSED */
        String dbState,
        String requestHash,
        String endpointSignature,
        Integer statusCode,
        String contentType,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        /** ISO timestamp when the Redis in-flight lock was acquired, if present. */
        String lockedAt) {
}
