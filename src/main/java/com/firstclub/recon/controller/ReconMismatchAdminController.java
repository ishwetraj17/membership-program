package com.firstclub.recon.controller;

import com.firstclub.recon.dto.ReconMismatchDTO;
import com.firstclub.recon.dto.ReconMismatchResolveRequestDTO;
import com.firstclub.recon.entity.ReconMismatchStatus;
import com.firstclub.recon.service.AdvancedReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/admin/recon")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Recon Mismatches", description = "Mismatch lifecycle management and taxonomy-aware recon runs")
public class ReconMismatchAdminController {

    private final AdvancedReconciliationService service;

    // ── Mismatch listing ──────────────────────────────────────────────────────

    @GetMapping("/mismatches")
    @Operation(summary = "List mismatches, optionally filtered by status")
    public ResponseEntity<Page<ReconMismatchDTO>> listMismatches(
            @RequestParam(required = false) ReconMismatchStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(service.listMismatches(status, pageable));
    }

    // ── Mismatch lifecycle ────────────────────────────────────────────────────

    @PostMapping("/mismatches/{mismatchId}/acknowledge")
    @Operation(summary = "Acknowledge a mismatch and assign an owner")
    public ResponseEntity<ReconMismatchDTO> acknowledge(
            @PathVariable Long mismatchId,
            @RequestBody Map<String, Long> body) {
        Long ownerUserId = body.get("ownerUserId");
        return ResponseEntity.ok(service.acknowledgeMismatch(mismatchId, ownerUserId));
    }

    @PostMapping("/mismatches/{mismatchId}/resolve")
    @Operation(summary = "Mark a mismatch as resolved with a resolution note")
    public ResponseEntity<ReconMismatchDTO> resolve(
            @PathVariable Long mismatchId,
            @RequestBody ReconMismatchResolveRequestDTO req) {
        return ResponseEntity.ok(service.resolveMismatch(mismatchId, req.getResolutionNote(), req.getOwnerUserId()));
    }

    @PostMapping("/mismatches/{mismatchId}/ignore")
    @Operation(summary = "Ignore a mismatch with a reason")
    public ResponseEntity<ReconMismatchDTO> ignore(
            @PathVariable Long mismatchId,
            @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "");
        return ResponseEntity.ok(service.ignoreMismatch(mismatchId, reason));
    }

    // ── Phase 14: Taxonomy-aware run and specialist queries ───────────────────

    /**
     * Trigger a full taxonomy-aware reconciliation run for {@code date}.
     *
     * <p>This run uses the configurable timing window (default 30 min) to avoid
     * false positives at day boundaries, and classifies each mismatch with an
     * {@link com.firstclub.recon.classification.ReconExpectation} and
     * {@link com.firstclub.recon.classification.ReconSeverity}.
     */
    @PostMapping("/run")
    @Operation(summary = "Trigger taxonomy-aware reconciliation run for a date")
    public ResponseEntity<List<ReconMismatchDTO>> run(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<ReconMismatchDTO> results = service.runForDateWithWindow(date);
        return ResponseEntity.ok(results);
    }

    /**
     * Returns all mismatches classified as
     * {@link com.firstclub.recon.entity.MismatchType#ORPHAN_GATEWAY_PAYMENT}.
     *
     * <p>These represent gateway transactions where real funds were received
     * but no invoice exists in the billing system — a critical data-integrity gap.
     */
    @GetMapping("/orphaned-gateway-payments")
    @Operation(summary = "List all orphaned gateway payment mismatches")
    public ResponseEntity<List<ReconMismatchDTO>> listOrphanedGatewayPayments() {
        return ResponseEntity.ok(service.listOrphanedGatewayPayments());
    }
}
