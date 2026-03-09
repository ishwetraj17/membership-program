package com.firstclub.platform.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Platform-wide abstraction over Redis string operations.
 *
 * <p>All Redis I/O in the platform must go through this facade — never call
 * {@link StringRedisTemplate} methods directly in business code. This ensures:
 * <ul>
 *   <li>Consistent error classification via {@link RedisErrorClassification}.</li>
 *   <li>Uniform logging with key and classification context.</li>
 *   <li>Single point for future circuit breaking or metric tagging.</li>
 * </ul>
 *
 * <h3>Conditional registration</h3>
 * <p>This bean is only created when {@code app.redis.enabled=true}.
 * When Redis is disabled, callers must guard with
 * {@link RedisAvailabilityService#isAvailable()} and fall back to their
 * database source of truth.
 *
 * <h3>Failure handling</h3>
 * <p>Each operation catches {@link RuntimeException} from Lettuce/Spring-Data-Redis,
 * classifies it via {@link RedisErrorClassification#classify(RuntimeException)},
 * and either returns a safe empty value (for reads) or logs and re-throws
 * (for writes that the caller declared critical).
 *
 * <p><b>Redis is never the source of truth for financial data.</b>
 * Every caller is responsible for a DB fallback when this facade returns empty.
 *
 * @see RedisKeyFactory
 * @see RedisTtlConfig
 * @see RedisErrorClassification
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisOpsFacade {

    private final StringRedisTemplate template;

    public RedisOpsFacade(StringRedisTemplate stringRedisTemplate) {
        this.template = stringRedisTemplate;
    }

    // ── Write ops ──────────────────────────────────────────────────────────────

    /**
     * Stores a key-value pair with a TTL, only if the key does not already exist
     * (Redis {@code SET NX EX}).
     *
     * <p>This is the canonical operation for acquiring distributed locks and
     * setting idempotency in-flight markers.
     *
     * @param key   Redis key (from {@link RedisKeyFactory})
     * @param value value string to store
     * @param ttl   duration until the key expires; must be positive
     * @return {@code true} if the key was set, {@code false} if it already existed
     *         or if Redis was unreachable ({@link RedisFailureBehavior#ALLOW} semantics)
     */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        try {
            Boolean result = template.opsForValue().setIfAbsent(key, value, ttl);
            return Boolean.TRUE.equals(result);
        } catch (RuntimeException ex) {
            RedisErrorClassification classification = RedisErrorClassification.classify(ex);
            log.warn("Redis setIfAbsent failed [key={} classification={}]: {}",
                    key, classification, ex.getMessage());
            return false;
        }
    }

    /**
     * Stores a key-value pair with a TTL (unconditional {@code SET EX}).
     *
     * @param key   Redis key
     * @param value value string to store
     * @param ttl   duration until the key expires; must be positive
     */
    public void set(String key, String value, Duration ttl) {
        try {
            template.opsForValue().set(key, value, ttl);
        } catch (RuntimeException ex) {
            RedisErrorClassification classification = RedisErrorClassification.classify(ex);
            log.warn("Redis set failed [key={} classification={}]: {}",
                    key, classification, ex.getMessage());
        }
    }

    // ── Read ops ───────────────────────────────────────────────────────────────

    /**
     * Fetches the string value for a key.
     *
     * <p>Returns {@link Optional#empty()} both when the key is absent and when
     * Redis is unavailable. Callers must treat an empty result as a cache miss
     * and fall back to their database.
     *
     * @param key Redis key
     * @return value wrapped in Optional, or empty on miss or error
     */
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(template.opsForValue().get(key));
        } catch (RuntimeException ex) {
            RedisErrorClassification classification = RedisErrorClassification.classify(ex);
            log.warn("Redis get failed [key={} classification={}]: {}",
                    key, classification, ex.getMessage());
            return Optional.empty();
        }
    }

    // ── Delete ops ─────────────────────────────────────────────────────────────

    /**
     * Deletes a key from Redis.
     *
     * <p>Failures are logged but not propagated — a failed delete means the key
     * will expire on its own TTL and the DB remains authoritative.
     *
     * @param key Redis key to delete
     * @return {@code true} if the key was deleted, {@code false} if not found or
     *         on any error
     */
    public boolean delete(String key) {
        try {
            return Boolean.TRUE.equals(template.delete(key));
        } catch (RuntimeException ex) {
            RedisErrorClassification classification = RedisErrorClassification.classify(ex);
            log.warn("Redis delete failed [key={} classification={}]: {}",
                    key, classification, ex.getMessage());
            return false;
        }
    }

    // ── Counter ops ────────────────────────────────────────────────────────────

    /**
     * Atomically increments the integer value of a key by 1 (Redis {@code INCR}).
     *
     * <p>If the key does not exist, Redis initialises it to 0 and then increments.
     * Returns {@code -1} on any Redis failure so callers can detect and fall back.
     *
     * @param key Redis key (should be a counter key from {@link RedisKeyFactory})
     * @return the new counter value after increment, or {@code -1} on error
     */
    public long increment(String key) {
        try {
            Long result = template.opsForValue().increment(key);
            return (result != null) ? result : -1L;
        } catch (RuntimeException ex) {
            RedisErrorClassification classification = RedisErrorClassification.classify(ex);
            log.warn("Redis increment failed [key={} classification={}]: {}",
                    key, classification, ex.getMessage());
            return -1L;
        }
    }

    /**
     * Sets or refreshes the TTL of an existing key (Redis {@code EXPIRE}).
     *
     * <p>Useful for extending a lock or rate-window counter after creation.
     * Failures are logged but not propagated — the key retains its existing TTL.
     *
     * @param key Redis key
     * @param ttl new duration until expiry; must be positive
     * @return {@code true} if the TTL was updated, {@code false} otherwise
     */
    public boolean expire(String key, Duration ttl) {
        try {
            return Boolean.TRUE.equals(
                    template.expire(key, ttl.toMillis(), TimeUnit.MILLISECONDS));
        } catch (RuntimeException ex) {
            RedisErrorClassification classification = RedisErrorClassification.classify(ex);
            log.warn("Redis expire failed [key={} classification={}]: {}",
                    key, classification, ex.getMessage());
            return false;
        }
    }

    // ── Lua script execution ──────────────────────────────────────────────────

    /**
     * Executes a Lua script atomically on the Redis server ({@code EVALSHA} / {@code EVAL}).
     *
     * <p>All Redis distributed-lock operations MUST use this method rather than
     * individual commands, because atomicity between GET and SET/DEL is only
     * guaranteed inside a single Lua execution context.
     *
     * @param script  precompiled script with SHA-1 and return type (use {@link LockScriptRegistry})
     * @param keys    list of Redis key names passed as KEYS[1], KEYS[2], …
     * @param args    additional arguments passed as ARGV[1], ARGV[2], …;
     *                Strings and Longs are serialised as UTF-8 strings
     * @param <T>     return type (typically {@link Long}: 0=fail, 1=success)
     * @return the script's return value, or {@code null} on Redis error
     */
    public <T> T executeLuaScript(
            org.springframework.data.redis.core.script.RedisScript<T> script,
            java.util.List<String> keys,
            Object... args) {
        try {
            return template.execute(script, keys, args);
        } catch (RuntimeException ex) {
            RedisErrorClassification classification = RedisErrorClassification.classify(ex);
            log.warn("Redis executeLuaScript failed [script={} classification={}]: {}",
                    script.getSha1(), classification, ex.getMessage());
            return null;
        }
    }

    /**
     * Non-varargs overload of {@link #executeLuaScript} — preferred when the
     * args array is built programmatically, and required for clean Mockito
     * stubbing in unit tests (varargs matchers have known ambiguity with
     * {@code Object...} when multiple args are passed).
     *
     * @param script precompiled script
     * @param keys   list of Redis key names
     * @param args   argument array passed as ARGV[1], ARGV[2], …
     * @param <T>    return type
     * @return the script's return value, or {@code null} on Redis error
     */
    public <T> T runLuaScript(
            org.springframework.data.redis.core.script.RedisScript<T> script,
            java.util.List<String> keys,
            Object[] args) {
        try {
            return template.execute(script, keys, args);
        } catch (RuntimeException ex) {
            RedisErrorClassification classification = RedisErrorClassification.classify(ex);
            log.warn("Redis runLuaScript failed [script={} classification={}]: {}",
                    script.getSha1(), classification, ex.getMessage());
            return null;
        }
    }

    // ── Pipeline ops ───────────────────────────────────────────────────────────

    /**
     * Executes multiple Redis commands in a single network round-trip using a
     * {@link SessionCallback} pipeline.
     *
     * <p>Use this when sending several related commands (e.g., INCR + EXPIRE for
     * a rate-limit counter) to reduce latency.
     *
     * <p>Pipeline commands are sent to Redis atomically within a single connection
     * but are <em>not</em> wrapped in a transaction — use
     * {@code MULTI/EXEC} via {@link StringRedisTemplate#executePipelined(SessionCallback)}
     * if you need transactional semantics.
     *
     * <p>Returns an empty list on any Redis error.
     *
     * @param callback the pipeline body; all commands within it are pipelined
     * @return list of results for each pipelined command, or empty on error
     */
    public List<Object> executePipelined(SessionCallback<Object> callback) {
        try {
            return template.executePipelined(callback);
        } catch (RuntimeException ex) {
            RedisErrorClassification classification = RedisErrorClassification.classify(ex);
            log.warn("Redis executePipelined failed [classification={}]: {}",
                    classification, ex.getMessage());
            return List.of();
        }
    }
}
