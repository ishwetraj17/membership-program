package com.firstclub.platform.scheduler.health;

import com.firstclub.platform.scheduler.entity.SchedulerExecutionHistory;
import com.firstclub.platform.scheduler.repository.SchedulerExecutionHistoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Admin endpoints for scheduler singleton health and execution history.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/v2/admin/schedulers/health}   — per-scheduler health summary</li>
 *   <li>{@code GET /api/v2/admin/schedulers/history}  — recent execution records</li>
 * </ul>
 *
 * <h3>Access</h3>
 * Requires {@code ADMIN} role.
 */
@RestController
@RequestMapping("/api/v2/admin/schedulers")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Ops Admin — Schedulers",
     description = "Scheduler singleton health monitoring, execution history, and stale detection.")
public class SchedulerOpsController {

    private final SchedulerHealthMonitor healthMonitor;
    private final SchedulerExecutionHistoryRepository historyRepo;

    /**
     * Returns the health status of all known schedulers.
     *
     * <p>A scheduler is considered STALE if it has not run successfully within
     * {@code expectedIntervalHours} (default: 25 h — one hour buffer above the
     * typical daily schedule).
     *
     * @param expectedIntervalHours expected maximum hours between successful runs (default 25)
     * @return list of health snapshots, one per distinct scheduler name
     */
    @GetMapping("/health")
    @Operation(
        summary = "Scheduler health summary",
        description = "Returns HEALTHY / STALE / NEVER_RAN for each known scheduler. "
            + "Use expectedIntervalHours to tune the staleness threshold."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Health summary returned successfully")
    })
    public ResponseEntity<List<SchedulerHealthResponseDTO>> schedulerHealth(
            @Parameter(description = "Max hours between successful runs before STALE (default 25)")
            @RequestParam(defaultValue = "25") int expectedIntervalHours) {

        Duration interval = Duration.ofHours(expectedIntervalHours);
        List<SchedulerHealthResponseDTO> response = healthMonitor.getAllSnapshots(interval)
                .stream()
                .map(SchedulerHealthResponseDTO::from)
                .toList();

        log.info("[SCHEDULER-OPS] Health check: {} schedulers, intervalHours={}",
                response.size(), expectedIntervalHours);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns execution history for a specific scheduler within a time window.
     *
     * @param schedulerName     required: logical scheduler name (e.g. {@code subscription-renewal})
     * @param limit             max records to return (default 20, max 100)
     * @return list of execution records newest-first
     */
    @GetMapping("/history")
    @Operation(
        summary = "Scheduler execution history",
        description = "Returns recent execution records for a named scheduler or all schedulers. "
            + "Results are ordered newest-first."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "History returned successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid parameters")
    })
    public ResponseEntity<List<SchedulerHistoryResponseDTO>> schedulerHistory(
            @Parameter(description = "Filter by scheduler name (optional — omit for all schedulers)")
            @RequestParam(required = false) String schedulerName,

            @Parameter(description = "Time window: hours of history to return (default 48)")
            @RequestParam(defaultValue = "48") int windowHours,

            @Parameter(description = "Max records to return (default 20, max 100)")
            @RequestParam(defaultValue = "20") int limit) {

        limit = Math.min(limit, 100);
        List<SchedulerExecutionHistory> rows;

        if (schedulerName != null && !schedulerName.isBlank()) {
            rows = historyRepo.findRecentBySchedulerName(schedulerName, limit);
        } else {
            Instant since = Instant.now().minus(windowHours, ChronoUnit.HOURS);
            rows = historyRepo.findAllSince(since);
            if (rows.size() > limit) {
                rows = rows.subList(0, limit);
            }
        }

        List<SchedulerHistoryResponseDTO> response = rows.stream()
                .map(SchedulerHistoryResponseDTO::from)
                .toList();

        log.info("[SCHEDULER-OPS] History: scheduler={} windowHours={} limit={} returned={}",
                schedulerName, windowHours, limit, response.size());
        return ResponseEntity.ok(response);
    }

    /**
     * Returns stale RUNNING records — execution rows that are still in RUNNING
     * status beyond the expected run duration, which typically indicates a crashed
     * node that never completed its status update.
     *
     * @param staleMinutes minutes after which a RUNNING record is considered stale (default 30)
     */
    @GetMapping("/stale-running")
    @Operation(
        summary = "Stale RUNNING scheduler records",
        description = "Returns executions stuck in RUNNING state beyond the expected run duration. "
            + "Empty response = all schedulers completed normally."
    )
    @ApiResponse(responseCode = "200", description = "Stale records returned (empty = none found)")
    public ResponseEntity<List<SchedulerHistoryResponseDTO>> staleRunning(
            @Parameter(description = "Minutes before a RUNNING record is considered stale (default 30)")
            @RequestParam(defaultValue = "30") int staleMinutes) {

        Duration threshold = Duration.ofMinutes(staleMinutes);
        List<SchedulerHistoryResponseDTO> response = healthMonitor
                .findStaleRunningRecords(threshold)
                .stream()
                .map(SchedulerHistoryResponseDTO::from)
                .toList();

        if (!response.isEmpty()) {
            log.warn("[SCHEDULER-OPS] Found {} stale RUNNING records (threshold={}min)",
                    response.size(), staleMinutes);
        }
        return ResponseEntity.ok(response);
    }
}
