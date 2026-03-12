package com.firstclub.platform.health;

import com.firstclub.platform.scheduler.health.SchedulerHealth;
import com.firstclub.platform.scheduler.health.SchedulerHealthMonitor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot Actuator {@link HealthIndicator} that reports the aggregate
 * staleness health of all registered background schedulers.
 *
 * <p>Contributes a {@code "schedulerStaleness"} key to
 * {@code GET /actuator/health/schedulerStaleness}.
 *
 * <h3>Status logic</h3>
 * <pre>
 *   All schedulers HEALTHY                   → UP
 *   Any scheduler STALE                      → UNKNOWN  (DEGRADED)
 *   Any critical scheduler NEVER_RAN         → UNKNOWN  (DEGRADED)
 *   All known schedulers NEVER_RAN (no data) → UP       (fresh deployment)
 * </pre>
 *
 * <h3>Expected interval</h3>
 * This indicator uses a conservative 30-minute window.  Individual scheduler
 * stale detection can be customised by calling
 * {@link SchedulerHealthMonitor#checkHealth(String, Duration)} directly.
 */
@Component
@RequiredArgsConstructor
public class SchedulerStalenessHealthIndicator implements HealthIndicator {

    /** Common expected-run interval applied to all known schedulers. */
    static final Duration EXPECTED_INTERVAL = Duration.ofMinutes(30);

    private final SchedulerHealthMonitor schedulerHealthMonitor;

    @Override
    public Health health() {
        List<SchedulerHealthMonitor.SchedulerStatusSnapshot> snapshots =
                schedulerHealthMonitor.getAllSnapshots(EXPECTED_INTERVAL);

        if (snapshots.isEmpty()) {
            // No schedulers have ever run — assume fresh deployment
            return Health.up()
                    .withDetail("message", "No scheduler execution history found — assuming fresh deployment")
                    .build();
        }

        Map<String, Object> details = new LinkedHashMap<>();
        boolean anyStale    = false;
        boolean anyNeverRan = false;

        for (SchedulerHealthMonitor.SchedulerStatusSnapshot snapshot : snapshots) {
            details.put(snapshot.schedulerName(), Map.of(
                    "health", snapshot.health().name(),
                    "lastSuccessAt", snapshot.lastSuccessAt() != null
                            ? snapshot.lastSuccessAt().toString() : "never",
                    "lastRunAt",     snapshot.lastRunAt() != null
                            ? snapshot.lastRunAt().toString() : "never"
            ));

            if (snapshot.health() == SchedulerHealth.STALE) {
                anyStale = true;
            } else if (snapshot.health() == SchedulerHealth.NEVER_RAN) {
                anyNeverRan = true;
            }
        }

        long staleCount    = snapshots.stream().filter(s -> s.health() == SchedulerHealth.STALE).count();
        long neverRanCount = snapshots.stream().filter(s -> s.health() == SchedulerHealth.NEVER_RAN).count();
        long healthyCount  = snapshots.stream().filter(s -> s.health() == SchedulerHealth.HEALTHY).count();

        if (anyStale) {
            return Health.unknown()
                    .withDetail("status", "DEGRADED")
                    .withDetail("totalSchedulers", snapshots.size())
                    .withDetail("staleCount", staleCount)
                    .withDetail("neverRanCount", neverRanCount)
                    .withDetail("healthyCount", healthyCount)
                    .withDetail("message", "One or more schedulers have not run within the expected interval")
                    .withDetails(details)
                    .build();
        }

        if (anyNeverRan && healthyCount > 0) {
            // Mix of healthy and never-ran — may be newly added schedulers
            return Health.unknown()
                    .withDetail("status", "DEGRADED")
                    .withDetail("totalSchedulers", snapshots.size())
                    .withDetail("staleCount", staleCount)
                    .withDetail("neverRanCount", neverRanCount)
                    .withDetail("healthyCount", healthyCount)
                    .withDetail("message", "Some schedulers have never run — check if they were recently added or are misconfigured")
                    .withDetails(details)
                    .build();
        }

        return Health.up()
                .withDetail("totalSchedulers", snapshots.size())
                .withDetail("healthyCount", healthyCount)
                .withDetail("neverRanCount", neverRanCount)
                .withDetails(details)
                .build();
    }
}
