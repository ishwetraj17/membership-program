package com.firstclub.platform.redis;

/**
 * Defines how callers must behave when a Redis operation fails.
 *
 * <p>Redis is a performance optimisation layer, not the source of truth.
 * When Redis is unavailable or returns an error, the platform must continue
 * functioning safely. This enum encodes the three valid failure responses:
 *
 * <ul>
 *   <li>{@link #ALLOW} — proceed silently; the caller uses its DB fallback.</li>
 *   <li>{@link #REJECT} — fail fast; the operation cannot safely continue without Redis.</li>
 *   <li>{@link #DEGRADE_TO_DB} — explicitly route to the database read path.</li>
 * </ul>
 *
 * <h3>Assignment guidelines</h3>
 * <table border="1">
 *   <tr><th>Use case</th><th>Recommended behavior</th></tr>
 *   <tr><td>Idempotency response cache miss</td><td>{@link #DEGRADE_TO_DB}</td></tr>
 *   <tr><td>Rate limit counter failure</td><td>{@link #ALLOW} (per-JVM fallback)</td></tr>
 *   <tr><td>Idempotency lock failure</td><td>{@link #ALLOW} (DB constraint is the safety net)</td></tr>
 *   <tr><td>Feature flag cache failure</td><td>{@link #DEGRADE_TO_DB}</td></tr>
 *   <tr><td>Routing rules cache failure</td><td>{@link #DEGRADE_TO_DB}</td></tr>
 *   <tr><td>Distributed lock — critical section</td><td>{@link #REJECT}</td></tr>
 * </table>
 *
 * <p><b>Never set {@link #REJECT} for financial reads.</b>
 * Rejecting a payment or subscription lookup because Redis is down is a
 * class of availability failure that must be reviewed by the team.
 *
 * @see RedisErrorClassification
 * @see RedisOpsFacade
 */
public enum RedisFailureBehavior {

    /**
     * Silently absorb the Redis failure and allow the operation to proceed.
     *
     * <p>The caller is responsible for its own DB fallback or in-memory default.
     * This is the correct choice for cache reads and best-effort rate limiting
     * where correctness is ensured by the database.
     */
    ALLOW("Absorb Redis failure silently; caller falls back to DB or in-memory default"),

    /**
     * Reject the operation and propagate the failure to the caller.
     *
     * <p>Use only when Redis unavailability represents a genuine safety risk,
     * such as a critical distributed lock that cannot be safely skipped.
     * Think carefully before choosing this option — it degrades availability.
     */
    REJECT("Propagate Redis failure to caller as an operational exception"),

    /**
     * Explicitly degrade to the authoritative database read path.
     *
     * <p>The caller signals that it should query PostgreSQL directly instead
     * of relying on a stale or missing cache entry.  Distinct from {@link #ALLOW}
     * in that it is an intentional, documented fallback path rather than
     * a silent skip.
     */
    DEGRADE_TO_DB("Explicitly route to the database as the fallback source of truth");

    private final String description;

    RedisFailureBehavior(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
