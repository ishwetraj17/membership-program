package com.firstclub.reporting.projections.controller;

import com.firstclub.reporting.projections.dto.LedgerBalanceSnapshotDTO;
import com.firstclub.reporting.projections.service.LedgerSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Admin API for ledger balance snapshot management.
 *
 * <p>Snapshots are point-in-time captures of each ledger account's balance.
 * Running a snapshot is idempotent: calling {@code POST /run} multiple times for
 * the same date returns the same result without creating duplicates.
 *
 * <p>Base path: {@code /api/v2/admin/ledger}
 */
@RestController
@RequestMapping("/api/v2/admin/ledger")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Ledger Snapshot Admin (V2)", description = "Capture and query point-in-time ledger balance snapshots")
public class LedgerSnapshotController {

    private final LedgerSnapshotService ledgerSnapshotService;

    /**
     * POST /api/v2/admin/ledger/balance-snapshots/run?date=YYYY-MM-DD
     *
     * <p>Compute current balances and persist snapshot rows for the given date.
     * Defaults to today when {@code date} is omitted.
     * Idempotent: re-running for the same date returns the existing snapshots.
     *
     * @param date the snapshot business date (ISO-8601, e.g. 2025-01-15)
     * @return list of snapshot DTOs — one per ledger account
     */
    @Operation(summary = "Run a ledger balance snapshot",
               description = "Captures current ledger balances for the given date. Idempotent.")
    @PostMapping("/balance-snapshots/run")
    public ResponseEntity<List<LedgerBalanceSnapshotDTO>> runSnapshot(

            @Parameter(description = "Business date for the snapshot (ISO-8601). Defaults to today.")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate snapshotDate = date != null ? date : LocalDate.now();
        List<LedgerBalanceSnapshotDTO> results = ledgerSnapshotService.generateSnapshotForDate(snapshotDate);
        return ResponseEntity.ok(results);
    }

    /**
     * GET /api/v2/admin/ledger/balance-snapshots
     *
     * <p>Return previously captured snapshots. All query parameters are optional.
     *
     * @param from       inclusive start date (ISO-8601)
     * @param to         inclusive end date   (ISO-8601)
     * @param merchantId tenant filter (null = all snapshots, including platform rows)
     */
    @Operation(summary = "List ledger balance snapshots",
               description = "Retrieve captured balance snapshots with optional date-range and merchant filters.")
    @GetMapping("/balance-snapshots")
    public ResponseEntity<List<LedgerBalanceSnapshotDTO>> getSnapshots(

            @Parameter(description = "Start date (ISO-8601, inclusive)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @Parameter(description = "End date (ISO-8601, inclusive)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @Parameter(description = "Filter by merchant ID (omit for platform-level snapshots)")
            @RequestParam(required = false) Long merchantId) {

        return ResponseEntity.ok(ledgerSnapshotService.getSnapshots(from, to, merchantId));
    }
}
