package com.firstclub.platform.health;

import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Spring Boot Actuator {@link HealthIndicator} for the outbox event backlog.
 *
 * <p>Contributes an {@code "outboxLag"} key to {@code GET /actuator/health/outboxLag}.
 *
 * <h3>Thresholds</h3>
 * <pre>
 *   failed = 0  AND  pending < 500   → UP       (HEALTHY)
 *   failed > 0  OR   pending ≥ 500   → UNKNOWN  (DEGRADED)
 *   pending ≥ 5 000                  → DOWN
 * </pre>
 *
 * <h3>Rationale</h3>
 * A non-zero failed count means at least one event has exhausted all retries
 * and is stuck — this is always worthy of attention. A large pending backlog
 * means the dispatcher is falling behind real-time event production.
 */
@Component
@RequiredArgsConstructor
public class OutboxLagHealthIndicator implements HealthIndicator {

    static final long DEGRADED_PENDING_THRESHOLD = 500L;
    static final long  DOWN_PENDING_THRESHOLD    = 5_000L;

    private final OutboxEventRepository outboxEventRepository;

    @Override
    public Health health() {
        long failed  = outboxEventRepository.countByStatus(OutboxEventStatus.FAILED);
        long pending = outboxEventRepository.countByStatus(OutboxEventStatus.NEW)
                     + outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSING);

        if (pending >= DOWN_PENDING_THRESHOLD) {
            return Health.down()
                    .withDetail("pendingCount", pending)
                    .withDetail("failedCount", failed)
                    .withDetail("message", "Outbox backlog critically high — dispatcher is severely behind")
                    .build();
        }

        if (failed > 0 || pending >= DEGRADED_PENDING_THRESHOLD) {
            return Health.unknown()
                    .withDetail("status", "DEGRADED")
                    .withDetail("pendingCount", pending)
                    .withDetail("failedCount", failed)
                    .withDetail("message", failed > 0
                            ? "Outbox has failed events requiring DLQ review"
                            : "Outbox backlog above degraded threshold — dispatcher may be falling behind")
                    .build();
        }

        return Health.up()
                .withDetail("pendingCount", pending)
                .withDetail("failedCount", failed)
                .build();
    }
}
