package com.firstclub.recon.controller;

import com.firstclub.recon.dto.ReconMismatchDTO;
import com.firstclub.recon.dto.ReconReportDTO;
import com.firstclub.recon.dto.SettlementDTO;
import com.firstclub.recon.service.ReconciliationService;
import com.firstclub.recon.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Admin endpoints for daily reconciliation reports and settlement simulation.
 *
 * <p>All endpoints require the {@code ADMIN} role.
 */
@RestController
@RequestMapping("/api/v1/admin/recon")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Reconciliation (Admin)", description = "Daily recon reports and settlement")
public class ReconAdminController {

    private final ReconciliationService reconciliationService;
    private final SettlementService     settlementService;

    // -------------------------------------------------------------------------
    // GET /api/v1/admin/recon/daily?date=YYYY-MM-DD  — JSON
    // -------------------------------------------------------------------------

    @Operation(summary = "Get or generate daily recon report (JSON)")
    @GetMapping("/daily")
    public ResponseEntity<ReconReportDTO> getDailyJson(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        ReconReportDTO report = reconciliationService.getForDate(date);
        if (report == null) {
            // Run on-demand so admin can trigger without waiting for the scheduler
            report = reconciliationService.runForDate(date);
        }
        return ResponseEntity.ok(report);
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/admin/recon/daily.csv?date=YYYY-MM-DD  — CSV download
    // -------------------------------------------------------------------------

    @Operation(summary = "Download daily recon report as CSV")
    @GetMapping(value = "/daily.csv", produces = "text/csv")
    public ResponseEntity<String> getDailyCsv(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        ReconReportDTO report = reconciliationService.getForDate(date);
        if (report == null) {
            report = reconciliationService.runForDate(date);
        }

        String csv = buildCsv(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"recon-" + date + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/admin/recon/settle?date=YYYY-MM-DD  — trigger settlement
    // -------------------------------------------------------------------------

    @Operation(summary = "Simulate nightly settlement for a given date (ADMIN)")
    @PostMapping("/settle")
    public ResponseEntity<SettlementDTO> settle(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        SettlementDTO settlement = settlementService.settleForDate(date);
        return ResponseEntity.ok(settlement);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static String buildCsv(ReconReportDTO report) {
        StringBuilder sb = new StringBuilder();

        // Header row
        sb.append("Report Date,Expected Total,Actual Total,Variance,Mismatch Count\n");
        sb.append(csv(report.getReportDate().toString()))
                .append(',').append(csv(report.getExpectedTotal().toPlainString()))
                .append(',').append(csv(report.getActualTotal().toPlainString()))
                .append(',').append(csv(report.getVariance().toPlainString()))
                .append(',').append(report.getMismatchCount())
                .append('\n');

        if (!report.getMismatches().isEmpty()) {
            sb.append("\nMismatch ID,Type,Invoice ID,Payment ID,Details\n");
            for (ReconMismatchDTO m : report.getMismatches()) {
                sb.append(csv(m.getId() == null ? "" : m.getId().toString()))
                        .append(',').append(csv(m.getType().name()))
                        .append(',').append(csv(m.getInvoiceId() == null ? "" : m.getInvoiceId().toString()))
                        .append(',').append(csv(m.getPaymentId() == null ? "" : m.getPaymentId().toString()))
                        .append(',').append(csv(m.getDetails()))
                        .append('\n');
            }
        }

        return sb.toString();
    }

    /** Wrap a value in double quotes and escape any existing double quotes. */
    private static String csv(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
