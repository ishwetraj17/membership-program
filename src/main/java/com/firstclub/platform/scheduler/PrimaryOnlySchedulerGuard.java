package com.firstclub.platform.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Guards scheduler execution to the primary PostgreSQL node only.
 *
 * <h3>Purpose</h3>
 * In active-passive database deployments (streaming replication, RDS Multi-AZ,
 * Cloud SQL HA), application nodes may be connected through a connection proxy
 * that occasionally routes traffic to a replica.  Running a write-heavy scheduler
 * batch against a read-only replica causes transaction failures, degraded
 * throughput, or silent skips that appear as job success.
 *
 * <p>This guard makes the decision explicit:
 * <pre>
 *   if (!guard.canRunScheduler("subscription-renewal")) {
 *       recorder.recordSkipped("subscription-renewal", nodeId, "not-primary");
 *       return;
 *   }
 * </pre>
 *
 * <h3>Configuration</h3>
 * Enable/disable via {@code scheduler.primary-only.enabled} (default {@code true}).
 * When disabled, all nodes are allowed to run schedulers — useful in single-node
 * development environments where {@code pg_is_in_recovery()} would always return
 * {@code false} anyway.
 *
 * <h3>Relationship to advisory locks</h3>
 * This guard is complementary to — not a replacement for — advisory locking
 * ({@link com.firstclub.platform.scheduler.lock.SchedulerLockService}).  A primary-
 * only guard prevents replica nodes from attempting writes; advisory locking
 * prevents two primary nodes from executing the same job concurrently during a
 * short failover window where both nodes believe they are primary.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PrimaryOnlySchedulerGuard {

    private final DatabaseRoleChecker roleChecker;

    @Value("${scheduler.primary-only.enabled:true}")
    private boolean primaryOnlyEnabled;

    /**
     * Returns {@code true} if the current node is allowed to run the named
     * scheduler.
     *
     * <p>When {@code scheduler.primary-only.enabled=false}, always returns
     * {@code true}.  When enabled (default), returns {@code true} only if
     * {@link DatabaseRoleChecker#isPrimary()} confirms this is the primary node.
     *
     * @param schedulerName logical name of the scheduler (for logging)
     * @return {@code true} = proceed with execution; {@code false} = skip
     */
    public boolean canRunScheduler(String schedulerName) {
        if (!primaryOnlyEnabled) {
            log.debug("[SCHEDULER-GUARD] primary-only disabled — allowing scheduler={}", schedulerName);
            return true;
        }

        boolean primary = roleChecker.isPrimary();
        if (!primary) {
            log.info("[SCHEDULER-GUARD] Skipping scheduler={} — not primary node", schedulerName);
        }
        return primary;
    }

    /**
     * Returns whether the primary-only guard is active.
     * Useful for health endpoints and diagnostic logging.
     */
    public boolean isPrimaryOnlyEnabled() {
        return primaryOnlyEnabled;
    }
}
