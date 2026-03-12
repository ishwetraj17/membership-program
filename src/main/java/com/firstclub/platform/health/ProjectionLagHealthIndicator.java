package com.firstclub.platform.health;

import com.firstclub.reporting.projections.dto.ProjectionLagReport;
import com.firstclub.reporting.projections.service.ProjectionLagMonitor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spring Boot Actuator {@link HealthIndicator} for read-model projection staleness.
 *
 * <p>Contributes a {@code "projectionLag"} key to {@code GET /actuator/health/projectionLag}.
 *
 * <h3>Thresholds (worst projection determines overall status)</h3>
 * <pre>
 *   max lag < 300 s   ( 5 min)   → UP       (HEALTHY)
 *   max lag < 3 600 s (60 min)   → UNKNOWN  (DEGRADED)
 *   max lag ≥ 3 600 s (60 min)   → DOWN
 * </pre>
 *
 * <h3>Empty projections</h3>
 * A projection that has never been populated reports {@code lagSeconds = -1}.
 * This is treated as healthy (the system may be freshly started).
 */
@Component
@RequiredArgsConstructor
public class ProjectionLagHealthIndicator implements HealthIndicator {

    static final long DEGRADED_LAG_SECONDS = 300L;   //  5 minutes
    static final long DOWN_LAG_SECONDS     = 3_600L; // 60 minutes

    private final ProjectionLagMonitor projectionLagMonitor;

    @Override
    public Health health() {
        Map<String, ProjectionLagReport> allLags = projectionLagMonitor.checkAllProjections();

        long maxLag = allLags.values().stream()
                .mapToLong(ProjectionLagReport::getLagSeconds)
                .filter(l -> l >= 0)  // -1 = empty table, treat as healthy
                .max()
                .orElse(0L);

        // Build a map of projection → lagSeconds for the health detail
        Map<String, Object> lagDetails = allLags.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().getLagSeconds()));

        if (maxLag >= DOWN_LAG_SECONDS) {
            return Health.down()
                    .withDetail("maxLagSeconds", maxLag)
                    .withDetails(lagDetails)
                    .withDetail("message", "Projection lag critically high — read models are severely stale")
                    .build();
        }

        if (maxLag >= DEGRADED_LAG_SECONDS) {
            return Health.unknown()
                    .withDetail("status", "DEGRADED")
                    .withDetail("maxLagSeconds", maxLag)
                    .withDetails(lagDetails)
                    .withDetail("message", "Projection lag above acceptable threshold — read models may be stale")
                    .build();
        }

        return Health.up()
                .withDetail("maxLagSeconds", maxLag)
                .withDetails(lagDetails)
                .build();
    }
}
