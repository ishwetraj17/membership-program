package com.firstclub.platform.repair.controller;

import com.firstclub.platform.repair.RepairAction;
import com.firstclub.platform.repair.RepairActionRegistry;
import com.firstclub.platform.repair.RepairActionResult;
import com.firstclub.platform.repair.RepairAuditService;
import com.firstclub.platform.repair.dto.RepairAuditResponseDTO;
import com.firstclub.platform.repair.dto.RepairRequestDTO;
import com.firstclub.platform.repair.dto.RepairResponseDTO;
import com.firstclub.platform.repair.entity.RepairActionAudit;
import com.firstclub.platform.repair.repository.RepairActionAuditRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Admin API for executing safe repair actions and querying the audit log.
 *
 * <p>All endpoints require the {@code ADMIN} role. Dry-run is opt-in via a
 * {@code ?dryRun=true} query parameter or the {@code dryRun} field in the
 * request body.
 */
@RestController
@RequestMapping("/api/v2/admin/repair")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Repair Admin", description = "Safe, auditable repair actions for derived state and async failure recovery")
public class RepairAdminController {

    private final RepairActionRegistry          registry;
    private final RepairAuditService            auditService;
    private final RepairActionAuditRepository   auditRepository;

    // ── Invoice ──────────────────────────────────────────────────────────────

    @PostMapping("/invoice/{id}/recompute")
    @Operation(summary = "Recompute invoice totals from lines",
               description = "Reads all invoice lines and rewrites subtotal/discountTotal/taxTotal/grandTotal. Supports dry-run.")
    @ApiResponse(responseCode = "200", description = "Repair executed (or simulated if dryRun=true)")
    public ResponseEntity<RepairResponseDTO> recomputeInvoice(
            @Parameter(description = "Invoice id") @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestBody(required = false) RepairRequestDTO body) {

        RepairRequestDTO req = body != null ? body : new RepairRequestDTO(dryRun, null, null, Map.of());
        boolean effectiveDryRun = dryRun || req.dryRun();
        log.info("RepairAdmin: recompute invoice={} dryRun={}", id, effectiveDryRun);
        return executeAction("repair.invoice.recompute_totals",
                String.valueOf(id), Map.of(), effectiveDryRun, req.actorUserId(), req.reason());
    }

    // ── Projections ──────────────────────────────────────────────────────────

    @PostMapping("/projection/{name}/rebuild")
    @Operation(summary = "Rebuild a named projection",
               description = "Truncates and rebuilds a projection from domain_events. Supported names: customer_billing_summary, merchant_daily_kpi.")
    public ResponseEntity<RepairResponseDTO> rebuildProjection(
            @Parameter(description = "Projection name") @PathVariable String name,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestBody(required = false) RepairRequestDTO body) {

        RepairRequestDTO req = body != null ? body : new RepairRequestDTO(dryRun, null, null, Map.of());
        boolean effectiveDryRun = dryRun || req.dryRun();
        log.info("RepairAdmin: rebuild projection={} dryRun={}", name, effectiveDryRun);
        return executeAction("repair.projection.rebuild",
                name, Map.of(), effectiveDryRun, req.actorUserId(), req.reason());
    }

    // ── Ledger snapshots ─────────────────────────────────────────────────────

    @PostMapping("/ledger-snapshot/run")
    @Operation(summary = "Rebuild ledger balance snapshot for a date",
               description = "Regenerates ledger_balance_snapshots rows for the given date (YYYY-MM-DD). No dry-run.")
    public ResponseEntity<RepairResponseDTO> rebuildLedgerSnapshot(
            @RequestParam String date,
            @RequestBody(required = false) RepairRequestDTO body) {

        RepairRequestDTO req = body != null ? body : new RepairRequestDTO(false, null, null, Map.of());
        log.info("RepairAdmin: rebuild ledger snapshot date={}", date);
        return executeAction("repair.ledger.rebuild_snapshot",
                null, Map.of("date", date), false, req.actorUserId(), req.reason());
    }

    // ── Outbox ───────────────────────────────────────────────────────────────

    @PostMapping("/outbox/{id}/retry")
    @Operation(summary = "Retry a stuck outbox event",
               description = "Resets status → NEW and next_attempt_at to now so the poller picks it up.")
    public ResponseEntity<RepairResponseDTO> retryOutboxEvent(
            @Parameter(description = "OutboxEvent id") @PathVariable Long id,
            @RequestBody(required = false) RepairRequestDTO body) {

        RepairRequestDTO req = body != null ? body : new RepairRequestDTO(false, null, null, Map.of());
        log.info("RepairAdmin: retry outbox event={}", id);
        return executeAction("repair.outbox.retry_event",
                String.valueOf(id), Map.of(), false, req.actorUserId(), req.reason());
    }

    // ── Webhooks ─────────────────────────────────────────────────────────────

    @PostMapping("/webhook-delivery/{id}/retry")
    @Operation(summary = "Retry a GAVE_UP or FAILED webhook delivery",
               description = "Resets status → PENDING so the scheduler dispatches it again.")
    public ResponseEntity<RepairResponseDTO> retryWebhookDelivery(
            @Parameter(description = "MerchantWebhookDelivery id") @PathVariable Long id,
            @RequestBody(required = false) RepairRequestDTO body) {

        RepairRequestDTO req = body != null ? body : new RepairRequestDTO(false, null, null, Map.of());
        log.info("RepairAdmin: retry webhook delivery={}", id);
        return executeAction("repair.webhook.retry_delivery",
                String.valueOf(id), Map.of(), false, req.actorUserId(), req.reason());
    }

    // ── Recon ─────────────────────────────────────────────────────────────────

    @PostMapping("/recon/run")
    @Operation(summary = "Rerun reconciliation for a date",
               description = "Regenerates the recon report and mismatch rows for the given date (YYYY-MM-DD).")
    public ResponseEntity<RepairResponseDTO> runRecon(
            @RequestParam String date,
            @RequestBody(required = false) RepairRequestDTO body) {

        RepairRequestDTO req = body != null ? body : new RepairRequestDTO(false, null, null, Map.of());
        log.info("RepairAdmin: rerun recon date={}", date);
        return executeAction("repair.recon.run",
                null, Map.of("date", date), false, req.actorUserId(), req.reason());
    }

    // ── Revenue schedule ─────────────────────────────────────────────────────

    @PostMapping("/revenue-recognition/{invoiceId}/regenerate")
    @Operation(summary = "Regenerate revenue recognition schedule for a paid invoice",
               description = "Normal mode: idempotent. Force mode (?force=true or body params.force=true): " +
                             "deletes PENDING rows and regenerates with catch_up_run=true. " +
                             "POSTED rows are never deleted. Supports dry-run.")
    public ResponseEntity<RepairResponseDTO> regenerateRevenueSchedule(
            @Parameter(description = "Invoice id") @PathVariable Long invoiceId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestBody(required = false) RepairRequestDTO body) {

        RepairRequestDTO req = body != null ? body : new RepairRequestDTO(dryRun, null, null, java.util.Map.of());
        boolean effectiveDryRun = dryRun || req.dryRun();

        // Merge force flag from query param and body params
        java.util.Map<String, String> params = new java.util.HashMap<>(req.params());
        if (force) {
            params.put("force", "true");
        }

        log.info("RepairAdmin: regenerate revenue schedule invoice={} dryRun={} force={}",
                invoiceId, effectiveDryRun, params.getOrDefault("force", "false"));
        return executeAction("repair.revenue.regenerate_schedule",
                String.valueOf(invoiceId), params, effectiveDryRun, req.actorUserId(), req.reason());
    }

    // ── Audit log ─────────────────────────────────────────────────────────────

    @GetMapping("/audit")
    @Operation(summary = "List repair audit entries (most-recent-first)",
               description = "Paginated log of all repair action executions.")
    public ResponseEntity<List<RepairAuditResponseDTO>> getAudit(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Page<RepairActionAudit> audits = auditRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(page, Math.min(size, 200), Sort.by("createdAt").descending()));
        List<RepairAuditResponseDTO> body = audits.stream()
                .map(RepairAuditResponseDTO::of)
                .collect(Collectors.toList());
        return ResponseEntity.ok(body);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<RepairResponseDTO> executeAction(
            String repairKey,
            String targetId,
            Map<String, String> params,
            boolean dryRun,
            Long actorUserId,
            String reason) {

        RepairAction action = registry.findByKey(repairKey)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Repair action not found: " + repairKey));

        RepairAction.RepairContext context = new RepairAction.RepairContext(
                targetId, params, dryRun, actorUserId, reason);

        RepairActionResult result;
        try {
            result = action.execute(context);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("RepairAdmin: action {} rejected: {}", repairKey, e.getMessage());
            result = RepairActionResult.builder()
                    .repairKey(repairKey)
                    .success(false)
                    .dryRun(dryRun)
                    .errorMessage(e.getMessage())
                    .evaluatedAt(java.time.LocalDateTime.now())
                    .build();
        }

        RepairActionAudit audit = auditService.record(context, result);
        return ResponseEntity.ok(RepairResponseDTO.of(result, audit.getId()));
    }
}
