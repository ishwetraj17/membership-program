package com.firstclub.platform.health;

import com.firstclub.payments.repository.DeadLetterMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Spring Boot Actuator {@link HealthIndicator} for the dead-letter queue depth.
 *
 * <p>Contributes a {@code "dlqDepth"} key to {@code GET /actuator/health/dlqDepth}.
 *
 * <h3>Thresholds</h3>
 * <pre>
 *   depth = 0        → UP       (HEALTHY)
 *   0 < depth < 50   → UNKNOWN  (DEGRADED — operator should investigate)
 *   depth ≥ 50       → DOWN     (DLQ saturated — data loss risk)
 * </pre>
 *
 * <h3>Rationale</h3>
 * A non-zero DLQ means events have failed permanently after exhausting
 * all retry attempts. Each entry is a lost domain event that may represent
 * a financial discrepancy. Once the DLQ grows past 50 entries, the system
 * is likely experiencing systemic failures that require immediate attention.
 */
@Component
@RequiredArgsConstructor
public class DlqDepthHealthIndicator implements HealthIndicator {

    static final long DEGRADED_THRESHOLD = 1L;
    static final long DOWN_THRESHOLD     = 50L;

    private final DeadLetterMessageRepository deadLetterMessageRepository;

    @Override
    public Health health() {
        long depth = deadLetterMessageRepository.count();

        if (depth >= DOWN_THRESHOLD) {
            return Health.down()
                    .withDetail("dlqDepth", depth)
                    .withDetail("message", "DLQ depth critically high — risk of permanent event loss. Immediate operator action required.")
                    .build();
        }

        if (depth >= DEGRADED_THRESHOLD) {
            return Health.unknown()
                    .withDetail("status", "DEGRADED")
                    .withDetail("dlqDepth", depth)
                    .withDetail("message", "Dead-letter queue contains failed events. Use /ops/dlq to investigate and requeue.")
                    .build();
        }

        return Health.up()
                .withDetail("dlqDepth", depth)
                .build();
    }
}
