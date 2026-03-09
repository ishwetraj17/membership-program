package com.firstclub.platform.lock.metrics;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Structured observability for distributed lock operations.
 *
 * <p>All methods emit an MDC-enriched log line at {@code INFO} or {@code WARN}
 * level.  MDC entries are always cleaned up after the log call so they do not
 * leak to subsequent log lines on the same thread.
 *
 * <p>The consistent prefix format ({@code [LOCK-*]}) makes lock events easily
 * filterable in log aggregation systems (Splunk, Loki, etc.):
 * <pre>
 *   [LOCK-ACQUIRED]  resource=subscription/42 fenceToken=17 elapsedMs=3
 *   [LOCK-RELEASED]  resource=subscription/42 ownLock=true
 *   [LOCK-EXTENDED]  resource=subscription/42 success=true
 *   [LOCK-TIMEOUT]   resource=subscription/42 attempts=12
 *   [LOCK-FAILURE]   resource=subscription/42 reason=REDIS_UNAVAILABLE
 * </pre>
 *
 * <p>Future extension point: swap the log calls for a
 * {@code io.micrometer.core.instrument.MeterRegistry} counter/timer when
 * Micrometer is added to the platform.
 */
@Slf4j
@Component
public class LockMetricsService {

    // MDC key constants — used by log filters / alerting rules
    private static final String MDC_RESOURCE    = "lock.resource";
    private static final String MDC_FENCE_TOKEN = "lock.fenceToken";
    private static final String MDC_ELAPSED_MS  = "lock.elapsedMs";

    /**
     * Records a successful lock acquisition.
     *
     * @param resourceType resource type
     * @param resourceId   resource identifier
     * @param fenceToken   the fence token assigned to this lock holder
     * @param elapsed      time taken to acquire (zero for first-attempt successes)
     */
    public void recordAcquisition(String resourceType, String resourceId,
                                   long fenceToken, Duration elapsed) {
        try {
            MDC.put(MDC_RESOURCE,    resourceType + "/" + resourceId);
            MDC.put(MDC_FENCE_TOKEN, String.valueOf(fenceToken));
            MDC.put(MDC_ELAPSED_MS,  String.valueOf(elapsed.toMillis()));
            log.info("[LOCK-ACQUIRED] resource={}/{} fenceToken={} elapsedMs={}",
                    resourceType, resourceId, fenceToken, elapsed.toMillis());
        } finally {
            MDC.remove(MDC_RESOURCE);
            MDC.remove(MDC_FENCE_TOKEN);
            MDC.remove(MDC_ELAPSED_MS);
        }
    }

    /**
     * Records a lock release.
     *
     * @param resourceType resource type
     * @param resourceId   resource identifier
     * @param ownLock      {@code true} if the Lua script confirmed we deleted our
     *                     own lock; {@code false} if the lock had already expired
     *                     or been taken by another owner
     */
    public void recordRelease(String resourceType, String resourceId, boolean ownLock) {
        try {
            MDC.put(MDC_RESOURCE, resourceType + "/" + resourceId);
            log.info("[LOCK-RELEASED] resource={}/{} ownLock={}",
                    resourceType, resourceId, ownLock);
        } finally {
            MDC.remove(MDC_RESOURCE);
        }
    }

    /**
     * Records a lease extension attempt.
     *
     * @param resourceType resource type
     * @param resourceId   resource identifier
     * @param success      {@code true} if the PEXPIRE Lua script succeeded
     */
    public void recordExtension(String resourceType, String resourceId, boolean success) {
        try {
            MDC.put(MDC_RESOURCE, resourceType + "/" + resourceId);
            if (success) {
                log.info("[LOCK-EXTENDED] resource={}/{} success=true", resourceType, resourceId);
            } else {
                log.warn("[LOCK-EXTEND-FAILED] resource={}/{} — lock expired or stolen",
                        resourceType, resourceId);
            }
        } finally {
            MDC.remove(MDC_RESOURCE);
        }
    }

    /**
     * Records that {@code acquireWithRetry} exhausted the timeout window.
     *
     * @param resourceType resource type
     * @param resourceId   resource identifier
     * @param attempts     number of attempts made before giving up
     */
    public void recordAcquisitionTimeout(String resourceType, String resourceId, int attempts) {
        try {
            MDC.put(MDC_RESOURCE, resourceType + "/" + resourceId);
            log.warn("[LOCK-TIMEOUT] resource={}/{} attempts={}",
                    resourceType, resourceId, attempts);
        } finally {
            MDC.remove(MDC_RESOURCE);
        }
    }

    /**
     * Records a non-timeout acquisition failure (e.g. Redis unavailable,
     * fence token generation failure).
     *
     * @param resourceType resource type
     * @param resourceId   resource identifier
     * @param reason       short reason code, e.g. {@code "REDIS_UNAVAILABLE"}
     */
    public void recordAcquisitionFailure(String resourceType, String resourceId, String reason) {
        try {
            MDC.put(MDC_RESOURCE, resourceType + "/" + resourceId);
            log.warn("[LOCK-FAILURE] resource={}/{} reason={}", resourceType, resourceId, reason);
        } finally {
            MDC.remove(MDC_RESOURCE);
        }
    }
}
