package com.firstclub.ops.repair;

import com.firstclub.ops.timeline.OpsTimelineService;
import com.firstclub.platform.ops.dto.DlqEntryResponseDTO;
import com.firstclub.platform.ops.service.DlqOpsService;
import com.firstclub.platform.repair.RepairAction;
import com.firstclub.platform.repair.RepairActionRegistry;
import com.firstclub.platform.repair.RepairActionResult;
import com.firstclub.platform.repair.RepairAuditService;
import com.firstclub.recon.service.AdvancedReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Unified service for all manual repair / recovery operations exposed through
 * the Ops Command Center API ({@code /ops/...}).
 *
 * <p>Each method:
 * <ol>
 *   <li>Executes the repair by delegating to the appropriate registered
 *       {@link RepairAction} or low-level service.</li>
 *   <li>Writes an immutable audit record via {@link RepairAuditService}
 *       in a REQUIRES_NEW transaction (so audit is never suppressed by a
 *       repair rollback).</li>
 *   <li>Appends a manual timeline event via {@link OpsTimelineService}
 *       (swallowed on failure — timeline is derived data).</li>
 * </ol>
 *
 * <p><b>Package:</b> {@code com.firstclub.ops.repair}
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManualRepairService {

    private final RepairActionRegistry          registry;
    private final RepairAuditService            repairAuditService;
    private final OpsTimelineService            opsTimelineService;
    private final DlqOpsService                 dlqOpsService;
    private final AdvancedReconciliationService advancedReconciliationService;

    // ── Repair keys (must match the registered RepairAction.getRepairKey()) ──

    private static final String KEY_OUTBOX_RETRY   = "repair.outbox.retry_event";
    private static final String KEY_WEBHOOK_RETRY  = "repair.webhook.retry_delivery";
    private static final String KEY_RECON_RUN      = "repair.recon.run";
    private static final String KEY_INVOICE_RECOMP = "repair.invoice.recompute_totals";
    private static final String KEY_LEDGER_SNAPSHOT= "repair.ledger.rebuild_snapshot";

    // ── Outbox retry ──────────────────────────────────────────────────────────

    /**
     * Resets a stuck or failed outbox event to {@code NEW} so the poller will
     * re-process it.
     */
    @Transactional
    public RepairActionResult retryOutboxEvent(Long eventId, Long merchantId,
                                               Long actorUserId, String reason) {
        log.info("ManualRepair: retryOutboxEvent id={} merchant={} actor={}",
                eventId, merchantId, actorUserId);
        RepairAction.RepairContext ctx = ctx(String.valueOf(eventId),
                Map.of("merchantId", String.valueOf(merchantId)), actorUserId, reason);
        RepairActionResult result = executeViaRegistry(KEY_OUTBOX_RETRY, ctx);
        repairAuditService.record(ctx, result);
        opsTimelineService.recordOutboxRetry(eventId, merchantId, actorLabel(actorUserId));
        return result;
    }

    // ── Webhook delivery retry ────────────────────────────────────────────────

    /**
     * Resets a GAVE_UP or FAILED webhook delivery to PENDING so the retry
     * scheduler will pick it up on its next run.
     */
    @Transactional
    public RepairActionResult retryWebhookDelivery(Long deliveryId, Long merchantId,
                                                    Long actorUserId, String reason) {
        log.info("ManualRepair: retryWebhookDelivery id={} merchant={} actor={}",
                deliveryId, merchantId, actorUserId);
        RepairAction.RepairContext ctx = ctx(String.valueOf(deliveryId),
                Map.of("merchantId", String.valueOf(merchantId)), actorUserId, reason);
        RepairActionResult result = executeViaRegistry(KEY_WEBHOOK_RETRY, ctx);
        repairAuditService.record(ctx, result);
        opsTimelineService.recordWebhookRetry(deliveryId, merchantId, actorLabel(actorUserId));
        return result;
    }

    // ── Reconciliation re-run ─────────────────────────────────────────────────

    /**
     * Reruns the full reconciliation report for the given date, regenerating
     * {@code recon_reports} and {@code recon_mismatches} rows.
     */
    @Transactional
    public RepairActionResult rerunReconciliation(LocalDate date, Long merchantId,
                                                   Long actorUserId, String reason) {
        log.info("ManualRepair: rerunReconciliation date={} merchant={} actor={}",
                date, merchantId, actorUserId);
        RepairAction.RepairContext ctx = ctx(null,
                Map.of("date", date.toString(), "merchantId", String.valueOf(merchantId)),
                actorUserId, reason);
        RepairActionResult result = executeViaRegistry(KEY_RECON_RUN, ctx);
        repairAuditService.record(ctx, result);
        opsTimelineService.recordReconRerun(date, merchantId, actorLabel(actorUserId));
        return result;
    }

    // ── Invoice totals rebuild ────────────────────────────────────────────────

    /**
     * Recomputes an invoice's subtotal / tax / grand-total from its line items.
     */
    @Transactional
    public RepairActionResult rebuildInvoiceTotals(Long invoiceId, Long merchantId,
                                                    Long actorUserId, String reason) {
        log.info("ManualRepair: rebuildInvoiceTotals id={} merchant={} actor={}",
                invoiceId, merchantId, actorUserId);
        RepairAction.RepairContext ctx = ctx(String.valueOf(invoiceId),
                Map.of("merchantId", String.valueOf(merchantId)), actorUserId, reason);
        RepairActionResult result = executeViaRegistry(KEY_INVOICE_RECOMP, ctx);
        repairAuditService.record(ctx, result);
        opsTimelineService.recordInvoiceRebuild(invoiceId, merchantId, actorLabel(actorUserId));
        return result;
    }

    // ── Ledger snapshot rebuild ───────────────────────────────────────────────

    /**
     * Regenerates the ledger balance snapshot table for the specified date.
     * Defaults to today when {@code date} is null.
     */
    @Transactional
    public RepairActionResult rebuildLedgerSnapshot(LocalDate date, Long merchantId,
                                                     Long actorUserId, String reason) {
        LocalDate effectiveDate = date != null ? date : LocalDate.now();
        log.info("ManualRepair: rebuildLedgerSnapshot date={} merchant={} actor={}",
                effectiveDate, merchantId, actorUserId);
        RepairAction.RepairContext ctx = ctx(null,
                Map.of("date", effectiveDate.toString(), "merchantId", String.valueOf(merchantId)),
                actorUserId, reason);
        RepairActionResult result = executeViaRegistry(KEY_LEDGER_SNAPSHOT, ctx);
        repairAuditService.record(ctx, result);
        opsTimelineService.recordLedgerRebuild(merchantId, effectiveDate.toString(), actorLabel(actorUserId));
        return result;
    }

    // ── DLQ requeue ───────────────────────────────────────────────────────────

    /**
     * Re-enqueues a dead-letter message as a new outbox event and removes
     * the entry from the DLQ.
     */
    public RepairActionResult requeueDlqMessage(Long dlqId, Long merchantId,
                                                 Long actorUserId, String reason) {
        log.info("ManualRepair: requeueDlqMessage id={} merchant={} actor={}",
                dlqId, merchantId, actorUserId);
        try {
            DlqEntryResponseDTO entry = dlqOpsService.retryDlqEntry(dlqId);
            opsTimelineService.recordDlqRequeue(dlqId, merchantId, actorLabel(actorUserId));
            return RepairActionResult.builder()
                    .repairKey("repair.dlq.requeue")
                    .success(true)
                    .dryRun(false)
                    .details("DLQ message " + dlqId + " re-enqueued (outbox eventType="
                            + entry.outboxEventType() + ")")
                    .evaluatedAt(LocalDateTime.now())
                    .build();
        } catch (Exception ex) {
            log.error("ManualRepair: DLQ requeue failed id={}: {}", dlqId, ex.getMessage(), ex);
            return RepairActionResult.builder()
                    .repairKey("repair.dlq.requeue")
                    .success(false)
                    .dryRun(false)
                    .errorMessage(ex.getMessage())
                    .evaluatedAt(LocalDateTime.now())
                    .build();
        }
    }

    // ── Mismatch acknowledge ──────────────────────────────────────────────────

    /**
     * Marks a reconciliation mismatch as {@code ACKNOWLEDGED}, indicating an
     * operator has reviewed it and is tracking resolution.
     */
    public RepairActionResult acknowledgeMismatch(Long mismatchId, Long merchantId,
                                                   Long actorUserId, String reason) {
        log.info("ManualRepair: acknowledgeMismatch id={} merchant={} actor={}",
                mismatchId, merchantId, actorUserId);
        try {
            advancedReconciliationService.acknowledgeMismatch(mismatchId, actorUserId);
            opsTimelineService.recordMismatchAcknowledge(mismatchId, merchantId, actorLabel(actorUserId));
            return RepairActionResult.builder()
                    .repairKey("repair.mismatch.acknowledge")
                    .success(true)
                    .dryRun(false)
                    .details("Mismatch " + mismatchId + " set to ACKNOWLEDGED")
                    .evaluatedAt(LocalDateTime.now())
                    .build();
        } catch (Exception ex) {
            log.error("ManualRepair: acknowledgeMismatch failed id={}: {}", mismatchId, ex.getMessage(), ex);
            return RepairActionResult.builder()
                    .repairKey("repair.mismatch.acknowledge")
                    .success(false)
                    .dryRun(false)
                    .errorMessage(ex.getMessage())
                    .evaluatedAt(LocalDateTime.now())
                    .build();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RepairActionResult executeViaRegistry(String key, RepairAction.RepairContext ctx) {
        RepairAction action = registry.findByKey(key)
                .orElseThrow(() -> new IllegalStateException("Repair action not registered: " + key));
        return action.execute(ctx);
    }

    private static RepairAction.RepairContext ctx(String targetId, Map<String, String> params,
                                                   Long actorUserId, String reason) {
        return new RepairAction.RepairContext(targetId, params, false, actorUserId, reason);
    }

    private static String actorLabel(Long actorUserId) {
        return actorUserId != null ? "user:" + actorUserId : "system";
    }
}
