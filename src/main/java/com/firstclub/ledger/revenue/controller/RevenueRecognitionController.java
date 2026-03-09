package com.firstclub.ledger.revenue.controller;

import com.firstclub.ledger.revenue.dto.RevenueRecognitionReportDTO;
import com.firstclub.ledger.revenue.dto.RevenueRecognitionRunResponseDTO;
import com.firstclub.ledger.revenue.dto.RevenueRecognitionScheduleResponseDTO;
import com.firstclub.ledger.revenue.service.RevenueRecognitionPostingService;
import com.firstclub.ledger.revenue.service.RevenueRecognitionReportService;
import com.firstclub.ledger.revenue.service.RevenueRecognitionScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Admin endpoints for the Revenue Recognition Engine.
 *
 * <p>These endpoints are intentionally placed under {@code /api/v2/admin} to
 * signal that they require elevated access (e.g. internal role).  Authentication
 * / authorisation enforcement should be added at the security-filter layer.
 */
@RestController
@RequestMapping("/api/v2/admin/revenue-recognition")
@RequiredArgsConstructor
@Tag(name = "Revenue Recognition",
     description = "Admin endpoints for revenue recognition schedule management and reporting")
public class RevenueRecognitionController {

    private final RevenueRecognitionScheduleService scheduleService;
    private final RevenueRecognitionPostingService  postingService;
    private final RevenueRecognitionReportService   reportService;

    // -------------------------------------------------------------------------
    // Schedules
    // -------------------------------------------------------------------------

    @GetMapping("/schedules")
    @Operation(
            summary     = "List all recognition schedules",
            description = "Returns every row in the revenue_recognition_schedules table. "
                        + "Use the report endpoint for aggregated summaries.")
    public ResponseEntity<List<RevenueRecognitionScheduleResponseDTO>> getSchedules() {
        return ResponseEntity.ok(scheduleService.listAllSchedules());
    }

    // -------------------------------------------------------------------------
    // Manual / on-demand posting run
    // -------------------------------------------------------------------------

    @PostMapping("/run")
    @Operation(
            summary     = "Post due recognition schedules for a given date",
            description = "Posts all PENDING schedules whose recognition_date is on or before "
                        + "the supplied date.  Re-running the same date is idempotent: "
                        + "already-POSTED schedules are skipped automatically.")
    public ResponseEntity<RevenueRecognitionRunResponseDTO> run(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(postingService.postDueRecognitionsForDate(date));
    }

    // -------------------------------------------------------------------------
    // Reporting
    // -------------------------------------------------------------------------

    @GetMapping("/report")
    @Operation(
            summary     = "Revenue recognition summary for a date range",
            description = "Returns aggregated amounts and counts by status "
                        + "(POSTED / PENDING / FAILED) for schedules whose "
                        + "recognition_date falls within [from, to] (inclusive).")
    public ResponseEntity<RevenueRecognitionReportDTO> report(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.reportByDateRange(from, to));
    }
}
