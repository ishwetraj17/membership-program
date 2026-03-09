package com.firstclub.platform.lock.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Holder for the three Lua scripts that back the distributed lock lifecycle.
 *
 * <h3>Why Lua?</h3>
 * Redis executes Lua scripts atomically.  A GET followed by a conditional DEL
 * in application code is a TOCTOU race — another thread can delete/overwrite
 * the key between the two commands.  Lua scripts eliminate this race by running
 * the read-check-mutate sequence as a single indivisible operation.
 *
 * <h3>Scripts</h3>
 * <dl>
 *   <dt>ACQUIRE</dt>
 *   <dd>SET key value NX PX ttlMs — returns 1 if acquired, 0 if key exists.</dd>
 *
 *   <dt>EXTEND</dt>
 *   <dd>GET key, if value == ownerToken → PEXPIRE key newTtlMs — returns 1 on success,
 *       0 if owner mismatch (expired and re-acquired by someone else).</dd>
 *
 *   <dt>RELEASE</dt>
 *   <dd>GET key, if value == ownerToken → DEL key — returns 1 if deleted,
 *       0 if owner mismatch (we must not delete another caller's lock).</dd>
 * </dl>
 */
@Component
public class LockScriptRegistry {

    // ── Lua source ────────────────────────────────────────────────────────────

    /**
     * KEYS[1] = lock key
     * ARGV[1] = owner token (instanceId:threadId:uuid)
     * ARGV[2] = TTL in milliseconds
     * Returns 1 (acquired) or 0 (already locked)
     */
    private static final String ACQUIRE_LUA =
            "if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', tonumber(ARGV[2])) then\n"
          + "  return 1\n"
          + "else\n"
          + "  return 0\n"
          + "end";

    /**
     * KEYS[1] = lock key
     * ARGV[1] = owner token
     * ARGV[2] = new TTL in milliseconds
     * Returns 1 (extended) or 0 (owner mismatch — lock expired / stolen)
     */
    private static final String EXTEND_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then\n"
          + "  return redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]))\n"
          + "else\n"
          + "  return 0\n"
          + "end";

    /**
     * KEYS[1] = lock key
     * ARGV[1] = owner token
     * Returns 1 (deleted) or 0 (owner mismatch — must NOT delete another holder's lock)
     */
    private static final String RELEASE_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then\n"
          + "  return redis.call('DEL', KEYS[1])\n"
          + "else\n"
          + "  return 0\n"
          + "end";

    // ── Script instances (SHA1 pre-computed at startup for EVALSHA) ───────────

    private final DefaultRedisScript<Long> acquireScript;
    private final DefaultRedisScript<Long> extendScript;
    private final DefaultRedisScript<Long> releaseScript;

    public LockScriptRegistry() {
        acquireScript = new DefaultRedisScript<>(ACQUIRE_LUA, Long.class);
        extendScript  = new DefaultRedisScript<>(EXTEND_LUA,  Long.class);
        releaseScript = new DefaultRedisScript<>(RELEASE_LUA, Long.class);
    }

    public DefaultRedisScript<Long> acquireScript() { return acquireScript; }
    public DefaultRedisScript<Long> extendScript()  { return extendScript; }
    public DefaultRedisScript<Long> releaseScript() { return releaseScript; }
}
