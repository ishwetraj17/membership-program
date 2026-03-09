package com.firstclub.platform.scheduler.health;

import com.firstclub.platform.scheduler.entity.SchedulerExecutionHistory;
import com.firstclub.platform.scheduler.repository.SchedulerExecutionHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Monitors scheduler execution health by comparing the last successful run
 * against a caller-supplied expected interval.
 *
 * <h3>Stale detection algorithm</h3>
 * <ol>
 *   <li>Query {@link SchedulerExecutionHistoryRepository#findLatestSuccessBySchedulerName}</li>
 *   <li>If absent → {@link SchedulerHealth#NEVER_RAN}</li>
 *   <li>If {@code completedAt + expectedInterval < now} → {@link SchedulerHealth#STALE}</li>
 *   <li>Otherwise → {@link SchedulerHealth#HEALTHY}</li>
 * </ol>
 *
 * <h3>DB time</h3>
 * Comparisons are done against {@link Instant#now()} (JVM clock).  The
 * {@link SchedulerExecutionHistory#completedAt} is written by
 * {@link com.firstclub.platform.scheduler.SchedulerExecutionRecorder} which also
 * uses JVM clock.  For sub-second precision requirements, switch both writes and
 * reads to use {@code SELECT NOW() AT TIME ZONE 'UTC'} via a raw JDBC query.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulerHealthMonitor {

    private final SchedulerExecutionHistoryRepository historyRepo;

    /**
     * Returns the health status for a named scheduler.
     *
     * @param schedulerName    logical scheduler name (e.g. {@code "subscription-renewal"})
     * @param expectedInterval maximum expected interval between successful runs
     * @return {@link SchedulerHealth} — {@code HEALTHY}, {@code STALE}, or {@code NEVER_RAN}
     */
    @Transactional(readOnly = true)
    public SchedulerHealth checkHealth(String schedulerName, Duration expectedInterval) {
        Optional<SchedulerExecutionHistory> lastSuccess =
                historyRepo.findLatestSuccessBySchedulerName(schedulerName);

        if (lastSuccess.isEmpty()) {
            log.debug("[SCHEDULER-HEALTH] NEVER_RAN scheduler={}", schedulerName);
            return SchedulerHealth.NEVER_RAN;
        }

        Instant completedAt = lastSuccess.get().getCompletedAt();
        Instant staleThreshold = Instant.now().minus(expectedInterval);

        if (completedAt.isBefore(staleThreshold)) {
            log.warn("[SCHEDULER-HEALTH] STALE scheduler={} lastSuccess={} threshold={}",
                    schedulerName, completedAt, staleThreshold);
            return SchedulerHealth.STALE;
        }

        log.debug("[SCHEDULER-HEALTH] HEALTHY scheduler={} lastSuccess={}", schedulerName, completedAt);
        return SchedulerHealth.HEALTHY;
    }

    /**
     * Returns a {@link SchedulerStatusSnapshot} combining the health enum with
     * the raw last-success record for richer diagnostic output.
     *
     * @param schedulerName    logical scheduler name
     * @param expectedInterval maximum expected interval between successful runs
     * @return snapshot with health, last success time, and expected interval
     */
    @Transactional(readOnly = true)
    public SchedulerStatusSnapshot getSnapshot(String schedulerName, Duration expectedInterval) {
        Optional<SchedulerExecutionHistory> lastSuccess =
                historyRepo.findLatestSuccessBySchedulerName(schedulerName);
        Optional<SchedulerExecutionHistory> lastRun =
                historyRepo.findLatestBySchedulerName(schedulerName);

        SchedulerHealth health = computeHealth(lastSuccess, expectedInterval);
        return new SchedulerStatusSnapshot(
                schedulerName,
                health,
                lastSuccess.map(SchedulerExecutionHistory::getCompletedAt).orElse(null),
                lastRun.map(SchedulerExecutionHistory::getStartedAt).orElse(null),
                lastRun.map(SchedulerExecutionHistory::getStatus).orElse(null),
                expectedInterval
        );
    }

    /**
     * Returns snapshots for all schedulers that have ever appeared in the history
     * table, using the provided expected interval for each.
     *
     * @param expectedInterval common interval to apply across all known schedulers
     * @return list of snapshots (may be empty if no schedulers have run yet)
     */
    @Transactional(readOnly = true)
    public List<SchedulerStatusSnapshot> getAllSnapshots(Duration expectedInterval) {
        return historyRepo.findAllKnownSchedulerNames().stream()
                .map(name -> getSnapshot(name, expectedInterval))
                .toList();
    }

    /**
     * Returns stale RUNNING records that likely represent crashed scheduler
     * instances that never updated their status.
     *
     * @param runningThreshold how long a RUNNING record is tolerated before
     *                         being considered a crash remnant
     * @return list of stale running records (empty if all schedulers are healthy)
     */
    @Transactional(readOnly = true)
    public List<SchedulerExecutionHistory> findStaleRunningRecords(Duration runningThreshold) {
        Instant staleThreshold = Instant.now().minus(runningThreshold);
        return historyRepo.findStaleRunningRecords(staleThreshold);
    }

    // ── private ──────────────────────────────────────────────────────────────

    private SchedulerHealth computeHealth(Optional<SchedulerExecutionHistory> lastSuccess,
                                          Duration expectedInterval) {
        if (lastSuccess.isEmpty()) return SchedulerHealth.NEVER_RAN;
        Instant completedAt   = lastSuccess.get().getCompletedAt();
        Instant staleThreshold = Instant.now().minus(expectedInterval);
        return completedAt.isBefore(staleThreshold) ? SchedulerHealth.STALE : SchedulerHealth.HEALTHY;
    }

    // ── Inner snapshot record ─────────────────────────────────────────────────

    /**
     * Point-in-time snapshot of a single scheduler's health and last run metadata.
     */
    public record SchedulerStatusSnapshot(
            String schedulerName,
            SchedulerHealth health,
            Instant lastSuccessAt,
            Instant lastRunAt,
            String lastRunStatus,
            Duration expectedInterval
    ) {}
}
