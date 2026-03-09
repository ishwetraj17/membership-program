package com.firstclub.platform.lock.fencing;

import com.firstclub.platform.errors.StaleOperationException;
import com.firstclub.platform.redis.RedisKeyFactory;
import com.firstclub.platform.redis.RedisOpsFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Issues and validates fencing tokens for distributed lock holders.
 *
 * <h3>The fencing token problem</h3>
 * Even with a "safe" Lua-based lock release, Redis distributed locks are
 * <em>not</em> linearizable.  Consider:
 * <ol>
 *   <li>Thread A acquires the lock and receives fencing token {@code 33}.</li>
 *   <li>Thread A experiences a GC pause.  The lock TTL expires.</li>
 *   <li>Thread B acquires the lock and receives fencing token {@code 34}.</li>
 *   <li>Thread B writes to the DB with token {@code 34}.</li>
 *   <li>Thread A resumes and tries to write to the DB with stale token {@code 33}.</li>
 * </ol>
 * If the DB storage layer rejects writes whose token is lower than the last
 * committed token, step 5 is safely rejected — preventing a split-brain write.
 *
 * <h3>Token generation</h3>
 * Tokens are generated via Redis {@code INCR} on a per-resource key
 * {@code {env}:firstclub:fence:{resourceType}:{resourceId}}.  Each acquire
 * yields a strictly higher token than the previous one.
 *
 * <h3>Token enforcement</h3>
 * Call {@link #enforceTokenValidity} from the write path (repository / service
 * layer) <em>after</em> fetching the stored token from the DB row and before
 * committing the update.  Throw {@link StaleOperationException} (HTTP 409) if
 * the incoming token is smaller than the stored one.
 */
@Slf4j
@Service
public class FencingTokenService {

    private final ObjectProvider<RedisOpsFacade> redisProvider;
    private final RedisKeyFactory keyFactory;

    public FencingTokenService(ObjectProvider<RedisOpsFacade> redisProvider,
                                RedisKeyFactory keyFactory) {
        this.redisProvider = redisProvider;
        this.keyFactory    = keyFactory;
    }

    /**
     * Generates the next monotonically increasing fence token for the given
     * resource by incrementing a Redis counter.
     *
     * <p>The counter is never reset between lock acquisitions, so tokens are
     * globally monotonic regardless of how many times the lock has been
     * acquired and released.
     *
     * @param resourceType entity type, e.g. {@code "subscription"}
     * @param resourceId   entity identifier, e.g. {@code "42"}
     * @return a positive long token; the first call for a new resource returns {@code 1}
     * @throws IllegalStateException if Redis is unavailable
     */
    public long generateToken(String resourceType, String resourceId) {
        RedisOpsFacade ops = redisProvider.getIfAvailable();
        if (ops == null) {
            throw new IllegalStateException(
                    "[FENCE] Redis is required for fence token generation but is unavailable. "
                    + "resource=" + resourceType + "/" + resourceId);
        }
        String fenceKey = keyFactory.fenceTokenKey(resourceType, resourceId);
        long token = ops.increment(fenceKey);
        log.debug("[FENCE-GENERATED] resource={}/{} token={}", resourceType, resourceId, token);
        return token;
    }

    /**
     * Returns {@code true} if the incoming token is valid given the last
     * committed token stored in the DB row.
     *
     * <p>Valid means {@code incomingToken >= storedToken}.  Equal tokens are
     * accepted to support idempotent retries of the same lock holder.
     *
     * @param incomingToken the fence token carried by the current request
     * @param storedToken   the {@code last_fence_token} value on the DB entity
     */
    public boolean isTokenValid(long incomingToken, long storedToken) {
        return incomingToken >= storedToken;
    }

    /**
     * Enforces fence-token validity, throwing {@link StaleOperationException}
     * when the incoming token is stale.
     *
     * <p>Call this inside the write transaction, after loading the entity and
     * before persisting the update:
     *
     * <pre>{@code
     *   fencingTokenService.enforceTokenValidity(
     *       "subscription", sub.getId(),
     *       requestFenceToken, sub.getLastFenceToken());
     *   sub.setLastFenceToken(requestFenceToken);
     *   // proceed with update ...
     * }</pre>
     *
     * @param entityType     human-readable entity type (for the error message)
     * @param entityId       entity primary key (for the error message)
     * @param incomingToken  the fence token from the current lock holder
     * @param storedToken    the fence token already committed to the DB row
     * @throws StaleOperationException (HTTP 409) if {@code incomingToken < storedToken}
     */
    public void enforceTokenValidity(String entityType, Object entityId,
                                      long incomingToken, long storedToken) {
        if (!isTokenValid(incomingToken, storedToken)) {
            log.warn("[FENCE-REJECTED] entity={}/{} incoming={} stored={}",
                    entityType, entityId, incomingToken, storedToken);
            throw new StaleOperationException(
                    entityType, entityId,
                    "fenceToken>=" + storedToken,
                    "fenceToken=" + incomingToken);
        }
    }
}
