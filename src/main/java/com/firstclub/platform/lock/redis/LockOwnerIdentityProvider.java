package com.firstclub.platform.lock.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Generates unique lock-owner tokens that identify a specific acquisition
 * attempt on a specific host thread.
 *
 * <h3>Token format</h3>
 * <pre>
 *   {instanceId}:{threadId}:{uuid}
 *   e.g. "pod-7c9f-1a2b:42:550e8400-e29b-41d4-a716-446655440000"
 * </pre>
 *
 * <ul>
 *   <li><strong>instanceId</strong>: hostname + 8-char UUID prefix, computed
 *       once at startup.  Ensures tokens from different pods never collide.</li>
 *   <li><strong>threadId</strong>: JVM thread ID.  Differentiates concurrent
 *       acquisitions on the same node.</li>
 *   <li><strong>uuid</strong>: random UUID per acquisition.  Ensures uniqueness
 *       even if the same thread re-acquires the same lock.</li>
 * </ul>
 *
 * <p>The full token is stored as the Redis value for the lock key.  The release
 * and extend Lua scripts compare {@code GET key} with this token before
 * mutating — ensuring only the owner can modify its own lock.
 */
@Slf4j
@Component
public class LockOwnerIdentityProvider {

    private final String instanceId;

    public LockOwnerIdentityProvider() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            hostname = "unknown-host";
            log.warn("[LOCK-IDENTITY] Could not resolve hostname; using '{}': {}", hostname, ex.getMessage());
        }
        this.instanceId = hostname + "-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[LOCK-IDENTITY] Instance ID initialized: {}", instanceId);
    }

    /**
     * Generates a unique owner token for a single lock-acquisition attempt.
     *
     * <p>Every call returns a different value because of the random UUID suffix.
     */
    public String generateOwnerToken() {
        return instanceId + ":" + Thread.currentThread().getId() + ":" + UUID.randomUUID();
    }

    /** Returns the stable instance identifier (hostname + UUID prefix). */
    public String getInstanceId() {
        return instanceId;
    }
}
