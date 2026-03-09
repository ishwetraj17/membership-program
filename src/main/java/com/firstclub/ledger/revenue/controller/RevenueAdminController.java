package com.firstclub.ledger.revenue.controller;

import com.firstclub.ledger.revenue.dto.RevenueRecognitionScheduleResponseDTO;
import com.firstclub.ledger.revenue.dto.RevenueWaterfallProjectionDTO;
import com.firstclub.ledger.revenue.service.RevenueRecognitionScheduleService;
import com.firstclub.ledger.revenue.service.RevenueWaterfallProjectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Admin API for Phase 14 revenue hardening — waterfall projections and
 * per-invoice schedule views.
 *
 * <p>Parallel to (not a replacement of) the existing
 * {@link RevenueRecognitionController} at {@code /api/v2/admin/revenue-recognition}.
 */
@RestController
@RequestMapping("/api/v2/admin/revenue")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Revenue Admin (v2)", description = "Revenue waterfall projections and per-invoice schedule details (Phase 14)")
public class RevenueAdminController {

    private final RevenueWaterfallProjectionService waterfallService;
    private final RevenueRecognitionScheduleService scheduleService;

    // ── Waterfall projection ──────────────────────────────────────────────────

    @GetMapping("/waterfall")
    @Operation(summary = "Revenue waterfall for all merchants in a date range",
               description = "Returns daily billed / deferred / recognised totals across all merchants. " +
                             "Rows are created or updated by the recognition run scheduler.")
    @ApiResponse(responseCode = "200", description = "Waterfall rows (may be empty if no recognition has run)")
    public ResponseEntity<List<RevenueWaterfallProjectionDTO>> getWaterfall(
            @Parameter(description = "Start date (inclusive), ISO format YYYY-MM-DD")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date (inclusive), ISO format YYYY-MM-DD")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        log.debug("GET /api/v2/admin/revenue/waterfall from={} to={}", from, to);
        return ResponseEntity.ok(waterfallService.getWaterfallAllMerchants(from, to));
    }

    @GetMapping("/waterfall/merchant/{merchantId}")
    @Operation(summary = "Revenue waterfall for a specific merchant in a date range",
               description = "Returns daily totals scoped to one merchant.")
    public ResponseEntity<List<RevenueWaterfallProjectionDTO>> getWaterfallForMerchant(
            @Parameter(description = "Merchant id") @PathVariable Long merchantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        log.debug("GET /api/v2/admin/revenue/waterfall/merchant/{} from={} to={}", merchantId, from, to);
        return ResponseEntity.ok(waterfallService.getWaterfall(merchantId, from, to));
    }

    @PostMapping("/waterfall/merchant/{merchantId}/refresh")
    @Operation(summary = "Refresh waterfall projection for a merchant on a specific date",
               description = "Recomputes recognized_amount from POSTED schedule rows and upserts the projection row.")
    @ApiResponse(responseCode = "200", description = "Updated projection row")
    public ResponseEntity<RevenueWaterfallProjectionDTO> refreshWaterfall(
            @Parameter(description = "Merchant id") @PathVariable Long merchantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        log.info("POST /api/v2/admin/revenue/waterfall/merchant/{}/refresh date={}", merchantId, date);
        return ResponseEntity.ok(waterfallService.updateProjectionForDate(merchantId, date));
    }

    // ── Per-invoice schedules ─────────────────────────────────────────────────

    @GetMapping("/schedules/{invoiceId}")
    @Operation(summary = "Revenue recognition schedules for a specific invoice",
               description = "Returns all schedule rows for the invoice including Phase 14 fields " +
                             "(generationFingerprint, postingRunId, catchUpRun).")
    @ApiResponse(responseCode = "200", description = "Schedule rows for the invoice (empty if none generated yet)")
    public ResponseEntity<List<RevenueRecognitionScheduleResponseDTO>> getSchedulesByInvoice(
            @Parameter(description = "Invoice id") @PathVariable Long invoiceId) {

        log.debug("GET /api/v2/admin/revenue/schedules/{}", invoiceId);
        return ResponseEntity.ok(scheduleService.listSchedulesByInvoice(invoiceId));
    }
}
