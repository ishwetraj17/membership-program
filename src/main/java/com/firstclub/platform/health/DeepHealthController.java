package com.firstclub.platform.health;

import com.firstclub.platform.ops.dto.DeepHealthResponseDTO;
import com.firstclub.platform.ops.service.DeepHealthService;
import com.firstclub.platform.scheduler.health.SchedulerHealth;
import com.firstclub.platform.scheduler.health.SchedulerHealthMonitor;
import com.firstclub.platform.slo.SloStatus;
import com.firstclub.platform.slo.SloStatusEntry;
import com.firstclub.platform.slo.SloStatusService;
import com.firstclub.reporting.projections.dto.ProjectionLagReport;
import com.firstclub.reporting.projections.service.ProjectionLagMonitor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase 21 enhanced observability endpoints.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /ops/health/deep}  — composite health with scheduler staleness,
 *       projection lag, and SLO summary in a single response</li>
 *   <li>{@code GET /ops/slo/status}   — detailed SLO evaluation for all registered SLOs</li>
 * </ul>
 *
 * <h3>Relationship to existing endpoints</h3>
 * {@code GET /api/v2/admin/system/health/deep} (from {@link com.firstclub.platform.ops.controller.OpsAdminController})
 * returns the base operational counters only. This endpoint enriches that
 * response with scheduler and projection observability added in Phase 21.
 */
@RestController
@RequestMapping("/ops")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Ops — Observability", description = "Enhanced deep health, projection lag, scheduler staleness, and SLO status")
public class DeepHealthController {

    private static final Duration SCHEDULER_EXPECTED_INTERVAL = Duration.ofMinutes(30);

    private final DeepHealthService      deepHealthService;
    private final SchedulerHealthMonitor schedulerHealthMonitor;
    private final ProjectionLagMonitor   projectionLagMonitor;
    private final SloStatusService       sloStatusService;

    // ── GET /ops/health/deep ──────────────────────────────────────────────────

    @GetMapping("/health/deep")
    @Operation(
            summary = "Enhanced deep health report",
            description = "Composites base operational counters (outbox, DLQ, webhooks, recon) with " +
                          "scheduler staleness, projection lag, and a SLO status summary. " +
                          "Use this endpoint when you need a single authoritative snapshot of system health."
    )
    public ResponseEntity<ObservabilityHealthReportDTO> enhancedDeepHealth() {
        DeepHealthResponseDTO baseMetrics = deepHealthService.buildDeepHealthReport();
        List<SchedulerHealthMonitor.SchedulerStatusSnapshot> schedulers =
                schedulerHealthMonitor.getAllSnapshots(SCHEDULER_EXPECTED_INTERVAL);

        Map<String, ProjectionLagReport> rawLags = projectionLagMonitor.checkAllProjections();
        Map<String, Long> lagByName = rawLags.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().getLagSeconds()));
        long maxLag = lagByName.values().stream()
                .filter(l -> l >= 0)
                .max(Long::compareTo)
                .orElse(0L);

        List<SloStatusEntry> sloEntries = sloStatusService.getAllSloStatuses();
        String sloOverall = sloStatusService.overallSloStatus();
        long meeting  = sloEntries.stream().filter(e -> e.status() == SloStatus.MEETING).count();
        long atRisk   = sloEntries.stream().filter(e -> e.status() == SloStatus.AT_RISK).count();
        long breached = sloEntries.stream().filter(e -> e.status() == SloStatus.BREACHED).count();

        String overallStatus = computeOverallStatus(baseMetrics, schedulers, maxLag, breached, atRisk);

        return ResponseEntity.ok(new ObservabilityHealthReportDTO(
                overallStatus,
                baseMetrics,
                schedulers,
                lagByName,
                maxLag,
                sloEntries,
                sloOverall,
                meeting,
                atRisk,
                breached,
                LocalDateTime.now()));
    }

    // ── GET /ops/slo/status ───────────────────────────────────────────────────

    @GetMapping("/slo/status")
    @Operation(
            summary = "SLO status report",
            description = "Evaluates each registered Service Level Objective (SLO) against live counter and " +
                          "timer data from Micrometer. Counter-based SLOs are computed since the last pod " +
                          "restart. Use INSUFFICIENT_DATA status as a signal to watch rather than an alert."
    )
    public ResponseEntity<List<SloStatusEntry>> sloStatus() {
        return ResponseEntity.ok(sloStatusService.getAllSloStatuses());
    }

    // ── private ───────────────────────────────────────────────────────────────

    private static String computeOverallStatus(DeepHealthResponseDTO base,
                                               List<SchedulerHealthMonitor.SchedulerStatusSnapshot> schedulers,
                                               long maxLagSeconds,
                                               long slosBreached,
                                               long slosAtRisk) {
        if (!"HEALTHY".equals(base.overallStatus()) && !"UP".equals(base.overallStatus())) {
            if ("DOWN".equals(base.overallStatus())) return "DOWN";
            return "DEGRADED";
        }
        boolean schedulerStale = schedulers.stream()
                .anyMatch(s -> s.health() == SchedulerHealth.STALE);
        if (schedulerStale) return "DEGRADED";
        if (maxLagSeconds >= ProjectionLagHealthIndicator.DOWN_LAG_SECONDS) return "DOWN";
        if (maxLagSeconds >= ProjectionLagHealthIndicator.DEGRADED_LAG_SECONDS) return "DEGRADED";
        if (slosBreached > 0) return "DEGRADED";
        if (slosAtRisk   > 0) return "DEGRADED";
        return "HEALTHY";
    }
}
