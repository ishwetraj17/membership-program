package com.firstclub.platform.repair.actions;

import com.firstclub.platform.repair.RepairAction;
import com.firstclub.platform.repair.RepairActionResult;
import com.firstclub.reporting.projections.dto.RebuildResponseDTO;
import com.firstclub.reporting.projections.service.ProjectionRebuildService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Rebuilds a named projection table from the full domain-event log.
 *
 * <p>Supported projection names: {@code customer_billing_summary},
 * {@code merchant_daily_kpi}.
 *
 * <p><b>What changes:</b> all rows in the target projection table are
 * deleted and re-derived from {@code domain_events}.
 *
 * <p><b>What is never changed:</b> the {@code domain_events} table is
 * strictly read-only.
 *
 * <p><b>Dry-run:</b> supported — returns projection name and current row
 * count without truncating.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectionRebuildAction implements RepairAction {

    private final ProjectionRebuildService projectionRebuildService;
    private final ObjectMapper             objectMapper;

    @Override
    public String getRepairKey() { return "repair.projection.rebuild"; }

    @Override
    public String getTargetType() { return "PROJECTION"; }

    @Override
    public boolean supportsDryRun() { return true; }

    @Override
    @Transactional
    public RepairActionResult execute(RepairContext context) {
        String projectionName = context.targetId();
        if (projectionName == null || projectionName.isBlank()) {
            throw new IllegalArgumentException("targetId must be a projection name (e.g. customer_billing_summary)");
        }
        if (!ProjectionRebuildService.SUPPORTED_PROJECTIONS.contains(projectionName)) {
            throw new IllegalArgumentException("Unsupported projection: " + projectionName
                    + ". Supported: " + ProjectionRebuildService.SUPPORTED_PROJECTIONS);
        }

        if (context.dryRun()) {
            log.info("[DRY-RUN] ProjectionRebuildAction: would rebuild projection '{}'", projectionName);
            return RepairActionResult.builder()
                    .repairKey(getRepairKey())
                    .success(true)
                    .dryRun(true)
                    .details("DRY-RUN: projection '" + projectionName + "' would be truncated and rebuilt from domain_events")
                    .evaluatedAt(LocalDateTime.now())
                    .build();
        }

        RebuildResponseDTO response = switch (projectionName) {
            case "customer_billing_summary" -> projectionRebuildService.rebuildCustomerBillingSummaryProjection();
            case "merchant_daily_kpi"       -> projectionRebuildService.rebuildMerchantDailyKpiProjection();
            default -> throw new IllegalArgumentException("Unsupported projection: " + projectionName);
        };

        String afterJson = toJson(response);
        log.info("ProjectionRebuildAction: rebuilt '{}' — {} events processed, {} records",
                projectionName, response.getEventsProcessed(), response.getRecordsInProjection());

        return RepairActionResult.builder()
                .repairKey(getRepairKey())
                .success(true)
                .dryRun(false)
                .afterSnapshotJson(afterJson)
                .details("Projection '" + projectionName + "' rebuilt: "
                        + response.getEventsProcessed() + " events → "
                        + response.getRecordsInProjection() + " records")
                .evaluatedAt(LocalDateTime.now())
                .build();
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }
}
