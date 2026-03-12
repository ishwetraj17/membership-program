package com.firstclub.ops.repair.controller;

import com.firstclub.ops.repair.ManualRepairService;
import com.firstclub.ops.timeline.OpsTimelineService;
import com.firstclub.platform.repair.RepairActionResult;
import com.firstclub.reporting.ops.timeline.dto.TimelineEventDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
 * Ops Command Center — a single REST surface for all manual platform repair
 * and debugging operations.
 *
 * <p>All endpoints require the {@code ADMIN} role.
 *
 * <h3>Design intent</h3>
 * <ul>
 *   <li>Each {@code POST} write-path delegates to {@link ManualRepairService},
 *       which handles execution, audit, and timeline recording atomically.</li>
 *   <li>The {@code GET /ops/timeline} read endpoint gives operators a
 *       time-ordered view of what happened to any platform entity.</li>
 * </ul>
 *
 * <p><b>Base path:</b> {@code /ops}
 */
@RestController
@RequestMapping("/ops")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Ops Command Center", description = "Manual repair and recovery operations")
public class OpsCommandController {

    private final ManualRepairService  manualRepairService;
    private final OpsTimelineService   opsTimelineService;

    // ── Timeline read ─────────────────────────────────────────────────────────

    @GetMapping("/timeline")
    @Operation(
        summary = "Fetch entity timeline",
        description = "Returns all timeline events (domain events + manual repair actions) for "
                + "the specified entity, oldest first. entityType examples: CUSTOMER, SUBSCRIPTION, "
                + "INVOICE, PAYMENT_INTENT, REFUND, DISPUTE, RECON_MISMATCH."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Timeline events returned")
    })
    public ResponseEntity<List<TimelineEventDTO>> getTimeline(
            @RequestParam Long merchantId,
            @RequestParam String entityType,
            @RequestParam Long entityId) {

        log.debug("GET /ops/timeline merchant={} entityType={} entityId={}",
                merchantId, entityType, entityId);
        return ResponseEntity.ok(
                opsTimelineService.getEntityTimeline(merchantId, entityType, entityId));
    }

    // ── Outbox retry ──────────────────────────────────────────────────────────

    @PostMapping("/outbox/retry/{id}")
    @Operation(
        summary = "Retry a stuck outbox event",
        description = "Resets the outbox event to NEW so the background poller will "
                + "re-process it.  Writes an audit record and appends a timeline event."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Retry dispatched"),
        @ApiResponse(responseCode = "404", description = "Event not found")
    })
    public ResponseEntity<RepairActionResult> retryOutboxEvent(
            @PathVariable Long id,
            @RequestParam Long merchantId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false, defaultValue = "") String reason) {

        log.info("POST /ops/outbox/retry/{} merchant={} actor={}", id, merchantId, actorUserId);
        return ResponseEntity.ok(
                manualRepairService.retryOutboxEvent(id, merchantId, actorUserId, reason));
    }

    // ── Webhook retry ─────────────────────────────────────────────────────────

    @PostMapping("/webhooks/retry/{id}")
    @Operation(
        summary = "Retry a failed webhook delivery",
        description = "Resets the webhook delivery to PENDING so the scheduler will retry it."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Retry scheduled"),
        @ApiResponse(responseCode = "404", description = "Delivery not found")
    })
    public ResponseEntity<RepairActionResult> retryWebhookDelivery(
            @PathVariable Long id,
            @RequestParam Long merchantId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false, defaultValue = "") String reason) {

        log.info("POST /ops/webhooks/retry/{} merchant={} actor={}", id, merchantId, actorUserId);
        return ResponseEntity.ok(
                manualRepairService.retryWebhookDelivery(id, merchantId, actorUserId, reason));
    }

    // ── Reconciliation re-run ─────────────────────────────────────────────────

    @PostMapping("/recon/rerun")
    @Operation(
        summary = "Re-run reconciliation for a date",
        description = "Regenerates recon_reports and recon_mismatches rows for the given date. "
                + "Useful after data corrections or missed nightly runs."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Recon rerun triggered"),
        @ApiResponse(responseCode = "400", description = "Invalid date format")
    })
    public ResponseEntity<RepairActionResult> rerunReconciliation(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam Long merchantId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false, defaultValue = "") String reason) {

        log.info("POST /ops/recon/rerun date={} merchant={} actor={}", date, merchantId, actorUserId);
        return ResponseEntity.ok(
                manualRepairService.rerunReconciliation(date, merchantId, actorUserId, reason));
    }

    // ── Invoice rebuild ───────────────────────────────────────────────────────

    @PostMapping("/invoices/{id}/rebuild")
    @Operation(
        summary = "Rebuild invoice totals",
        description = "Recomputes subtotal, tax, and grand-total from line items. "
                + "Safe to call multiple times (idempotent)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Invoice totals rebuilt"),
        @ApiResponse(responseCode = "404", description = "Invoice not found"),
        @ApiResponse(responseCode = "409", description = "Invoice in non-recomputable state")
    })
    public ResponseEntity<RepairActionResult> rebuildInvoice(
            @PathVariable Long id,
            @RequestParam Long merchantId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false, defaultValue = "") String reason) {

        log.info("POST /ops/invoices/{}/rebuild merchant={} actor={}", id, merchantId, actorUserId);
        return ResponseEntity.ok(
                manualRepairService.rebuildInvoiceTotals(id, merchantId, actorUserId, reason));
    }

    // ── Ledger snapshot rebuild ───────────────────────────────────────────────

    @PostMapping("/ledger/snapshots/rebuild")
    @Operation(
        summary = "Rebuild ledger balance snapshot",
        description = "Regenerates the ledger snapshot for the given date. "
                + "Defaults to today when date is omitted."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Ledger snapshot rebuilt")
    })
    public ResponseEntity<RepairActionResult> rebuildLedgerSnapshot(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam Long merchantId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false, defaultValue = "") String reason) {

        log.info("POST /ops/ledger/snapshots/rebuild date={} merchant={} actor={}",
                date, merchantId, actorUserId);
        return ResponseEntity.ok(
                manualRepairService.rebuildLedgerSnapshot(date, merchantId, actorUserId, reason));
    }

    // ── DLQ requeue ───────────────────────────────────────────────────────────

    @PostMapping("/dlq/{id}/requeue")
    @Operation(
        summary = "Re-enqueue a dead-letter message",
        description = "Moves a DLQ entry back to the outbox as a new event and removes it "
                + "from the dead-letter queue."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Message re-enqueued"),
        @ApiResponse(responseCode = "404", description = "DLQ entry not found")
    })
    public ResponseEntity<RepairActionResult> requeueDlqMessage(
            @PathVariable Long id,
            @RequestParam Long merchantId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false, defaultValue = "") String reason) {

        log.info("POST /ops/dlq/{}/requeue merchant={} actor={}", id, merchantId, actorUserId);
        return ResponseEntity.ok(
                manualRepairService.requeueDlqMessage(id, merchantId, actorUserId, reason));
    }

    // ── Mismatch acknowledge ──────────────────────────────────────────────────

    @PostMapping("/mismatches/{id}/acknowledge")
    @Operation(
        summary = "Acknowledge a reconciliation mismatch",
        description = "Sets the mismatch to ACKNOWLEDGED, indicating an operator has reviewed "
                + "it and is tracking resolution.  Can transition from any non-terminal state."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Mismatch acknowledged"),
        @ApiResponse(responseCode = "404", description = "Mismatch not found")
    })
    public ResponseEntity<RepairActionResult> acknowledgeMismatch(
            @PathVariable Long id,
            @RequestParam Long merchantId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false, defaultValue = "") String reason) {

        log.info("POST /ops/mismatches/{}/acknowledge merchant={} actor={}", id, merchantId, actorUserId);
        return ResponseEntity.ok(
                manualRepairService.acknowledgeMismatch(id, merchantId, actorUserId, reason));
    }
}
