package com.firstclub.platform.health;

import com.firstclub.platform.ops.dto.DeepHealthResponseDTO;
import com.firstclub.platform.scheduler.health.SchedulerHealthMonitor;
import com.firstclub.platform.slo.SloStatusEntry;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Composite deep-health report produced by {@link DeepHealthController}.
 *
 * <p>Extends the base {@link DeepHealthResponseDTO} with Phase 21 additions:
 * scheduler staleness, projection lag, and SLO status.
 *
 * @param overallStatus          HEALTHY / DEGRADED / DOWN
 * @param baseMetrics            base operational counters from {@link com.firstclub.platform.ops.service.DeepHealthService}
 * @param schedulers             per-scheduler staleness snapshots
 * @param projectionLagByName    projection name → lag in seconds (-1 if table empty)
 * @param maxProjectionLagSeconds worst-case lag across all projections
 * @param sloStatus              evaluated status of all registered SLOs
 * @param sloOverallStatus       HEALTHY / AT_RISK / CRITICAL
 * @param slosMeeting            count of SLOs currently meeting their target
 * @param slosAtRisk             count of SLOs at risk
 * @param slosBreached           count of SLOs currently breached
 * @param checkedAt              timestamp of this report
 */
public record ObservabilityHealthReportDTO(
        String                                              overallStatus,
        DeepHealthResponseDTO                               baseMetrics,
        List<SchedulerHealthMonitor.SchedulerStatusSnapshot> schedulers,
        Map<String, Long>                                   projectionLagByName,
        long                                                maxProjectionLagSeconds,
        List<SloStatusEntry>                                sloStatus,
        String                                              sloOverallStatus,
        long                                                slosMeeting,
        long                                                slosAtRisk,
        long                                                slosBreached,
        LocalDateTime                                       checkedAt
) {}
