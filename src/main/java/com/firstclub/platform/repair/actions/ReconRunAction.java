package com.firstclub.platform.repair.actions;

import com.firstclub.platform.repair.RepairAction;
import com.firstclub.platform.repair.RepairActionResult;
import com.firstclub.recon.dto.ReconReportDTO;
import com.firstclub.recon.service.ReconciliationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Reruns the full reconciliation report for a given date.
 *
 * <p><b>What changes:</b> the {@code recon_reports} and
 * {@code recon_mismatches} rows for the target date are regenerated.
 *
 * <p><b>What is never changed:</b> payment/settlement source rows;
 * only the derived report and mismatch records are produced.
 *
 * <p><b>Dry-run:</b> not supported.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconRunAction implements RepairAction {

    static final String PARAM_DATE = "date";

    private final ReconciliationService reconService;
    private final ObjectMapper          objectMapper;

    @Override
    public String getRepairKey() { return "repair.recon.run"; }

    @Override
    public String getTargetType() { return "RECON_REPORT"; }

    @Override
    public boolean supportsDryRun() { return false; }

    @Override
    @Transactional
    public RepairActionResult execute(RepairContext context) {
        String dateStr = context.param(PARAM_DATE);
        if (dateStr == null || dateStr.isBlank()) {
            throw new IllegalArgumentException("Parameter 'date' (YYYY-MM-DD) is required");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(dateStr);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format, expected YYYY-MM-DD: " + dateStr);
        }

        log.info("ReconRunAction: running reconciliation for date={}", date);
        ReconReportDTO report = reconService.runForDate(date);

        String afterJson = toJson(report);
        log.info("ReconRunAction: reconciliation complete for date={} mismatches={}",
                date, report.getMismatchCount());

        return RepairActionResult.builder()
                .repairKey(getRepairKey())
                .success(true)
                .dryRun(false)
                .afterSnapshotJson(afterJson)
                .details("Reconciliation rerun for date=" + date
                        + " mismatches=" + report.getMismatchCount())
                .evaluatedAt(LocalDateTime.now())
                .build();
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }
}
