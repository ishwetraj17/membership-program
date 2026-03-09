package com.firstclub.platform.redis;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.serializer.SerializationException;

/**
 * Classifies Redis exceptions into three operational error categories.
 *
 * <p>This classification drives circuit-breaking, retry decisions, and
 * metric tagging in {@link RedisOpsFacade}. Each category has a distinct
 * operational meaning:
 *
 * <ul>
 *   <li>{@link #TRANSIENT} — short-lived problem; retry with backoff is safe.</li>
 *   <li>{@link #UNAVAILABLE} — Redis node is unreachable; fall back to DB immediately.</li>
 *   <li>{@link #SERIALIZATION} — data corruption or schema mismatch; do not retry.</li>
 * </ul>
 *
 * <h3>Classification rules</h3>
 * <pre>
 * RedisConnectionFailureException → UNAVAILABLE
 * QueryTimeoutException           → TRANSIENT
 * RedisSystemException            → TRANSIENT (for timeouts/interrupts)
 *                                 → UNAVAILABLE (for connection errors)
 * SerializationException          → SERIALIZATION
 * All other RuntimeException      → TRANSIENT (conservative default)
 * </pre>
 *
 * <p>Callers should log the classification alongside the exception message
 * to aid post-incident analysis.
 *
 * @see RedisOpsFacade
 * @see RedisFailureBehavior
 */
public enum RedisErrorClassification {

    /**
     * Transient error — a retry after a brief backoff is likely to succeed.
     *
     * <p>Examples: command timeout, temporary network blip, Redis server
     * briefly overloaded. The underlying Redis instance is probably still
     * reachable.
     */
    TRANSIENT("Transient Redis error; retry with backoff is safe"),

    /**
     * Redis is completely unreachable.
     *
     * <p>Examples: connection refused, TCP RST, DNS resolution failure,
     * Redis instance not yet started. Do not retry immediately — trigger
     * the DB fallback path.
     */
    UNAVAILABLE("Redis node is unreachable; fall back to DB immediately"),

    /**
     * Serialisation or deserialisation failure.
     *
     * <p>Examples: JSON schema mismatch, Jackson type-mapping error, corrupted
     * cached value. Retrying will produce the same error. The cache entry
     * should be evicted and the value re-fetched from the DB.
     */
    SERIALIZATION("Data serialisation failure; evict cache entry and re-fetch from DB");

    private final String description;

    RedisErrorClassification(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Classifies a runtime exception from a Redis operation into one of the
     * three error categories.
     *
     * <p>This method is intentionally lenient: unknown exception types default
     * to {@link #TRANSIENT} rather than crashing the caller. Operators can
     * refine this mapping as new exception types are observed in production.
     *
     * @param ex the exception thrown by a Redis operation; must not be {@code null}
     * @return the appropriate error classification
     */
    public static RedisErrorClassification classify(RuntimeException ex) {
        if (ex instanceof SerializationException) {
            return SERIALIZATION;
        }
        if (ex instanceof RedisConnectionFailureException) {
            return UNAVAILABLE;
        }
        if (ex instanceof QueryTimeoutException) {
            return TRANSIENT;
        }
        if (ex instanceof RedisSystemException rse) {
            String msg = rse.getMessage() != null ? rse.getMessage().toLowerCase() : "";
            if (msg.contains("connect") || msg.contains("refused") || msg.contains("unreachable")) {
                return UNAVAILABLE;
            }
            return TRANSIENT;
        }
        // Conservative default: treat unknown exceptions as transient.
        // This avoids masking new failure modes as non-retryable.
        return TRANSIENT;
    }
}
