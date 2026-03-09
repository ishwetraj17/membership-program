package com.firstclub.platform.repair.actions;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.ledger.revenue.dto.RevenueRecognitionScheduleResponseDTO;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.ledger.revenue.service.RevenueRecognitionScheduleService;
import com.firstclub.platform.repair.RepairAction;
import com.firstclub.platform.repair.RepairActionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Regenerates missing or stale revenue recognition schedule rows for a paid invoice.
 *
 * <p><b>Safety guards:</b>
 * <ul>
 *   <li>Invoice must be in {@code PAID} status.</li>
 *   <li>Invoice must have a non-null {@code subscriptionId}, {@code periodStart},
 *       and {@code periodEnd}.</li>
 *   <li>Normal mode (default): idempotent — if schedules already exist they are returned
 *       without creating duplicates.</li>
 *   <li>Force mode ({@code params.force=true}, Phase 14): deletes all {@code PENDING}
 *       schedule rows and regenerates fresh rows marked with {@code catchUpRun=true}.
 *       POSTED rows are never deleted — they are financial source of truth.</li>
 * </ul>
 *
 * <p><b>What changes:</b> rows inserted (and potentially deleted) in
 * {@code revenue_recognition_schedules}.
 *
 * <p><b>What is never changed:</b> the invoice row, ledger entries, or any
 * already-{@code POSTED} schedule rows.
 *
 * <p><b>Dry-run:</b> supported — checks eligibility and reports what would be
 * created/deleted without persisting.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RevenueScheduleRegenerateAction implements RepairAction {

    private final InvoiceRepository                    invoiceRepository;
    private final RevenueRecognitionScheduleRepository scheduleRepository;
    private final RevenueRecognitionScheduleService    scheduleService;
    private final ObjectMapper                         objectMapper;

    @Override
    public String getRepairKey() { return "repair.revenue.regenerate_schedule"; }

    @Override
    public String getTargetType() { return "INVOICE"; }

    @Override
    public boolean supportsDryRun() { return true; }

    @Override
    @Transactional
    public RepairActionResult execute(RepairContext context) {
        Long invoiceId = parseId(context.targetId());
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));

        // Guard: must be PAID
        if (invoice.getStatus() != InvoiceStatus.PAID) {
            throw new IllegalStateException(
                    "Revenue schedule regeneration is only safe for PAID invoices. "
                    + "Invoice " + invoiceId + " is " + invoice.getStatus());
        }
        // Guard: must be a subscription invoice with a billing period
        if (invoice.getSubscriptionId() == null) {
            throw new IllegalStateException("Invoice " + invoiceId + " has no subscriptionId — cannot generate schedule");
        }
        if (invoice.getPeriodStart() == null || invoice.getPeriodEnd() == null) {
            throw new IllegalStateException("Invoice " + invoiceId + " has no period boundaries — cannot generate schedule");
        }

        // Phase 14: force=true → catch-up / force-regeneration mode
        boolean force = Boolean.parseBoolean(context.paramOrDefault("force", "false"));

        boolean alreadyExists = scheduleRepository.existsByInvoiceId(invoiceId);
        long pendingCount = alreadyExists
                ? scheduleRepository.findByInvoiceIdAndStatus(invoiceId, RevenueRecognitionStatus.PENDING).size()
                : 0;

        if (context.dryRun()) {
            String details = buildDryRunDetails(invoiceId, invoice, force, alreadyExists, pendingCount);
            log.info("[DRY-RUN] RevenueScheduleRegenerateAction: invoice={} alreadyExists={} force={} pendingRows={}",
                    invoiceId, alreadyExists, force, pendingCount);
            return RepairActionResult.builder()
                    .repairKey(getRepairKey())
                    .success(true)
                    .dryRun(true)
                    .details(details)
                    .evaluatedAt(LocalDateTime.now())
                    .build();
        }

        List<RevenueRecognitionScheduleResponseDTO> schedules;
        if (force) {
            // Force-regeneration: delete PENDING rows, regenerate with catchUpRun=true
            schedules = scheduleService.regenerateScheduleForInvoice(invoiceId);
            log.info("RevenueScheduleRegenerateAction (FORCE): invoice={} regenerated={} rows (catch-up)",
                    invoiceId, schedules.size());
        } else {
            // Normal idempotent regeneration
            schedules = scheduleService.generateScheduleForInvoice(invoiceId);
            log.info("RevenueScheduleRegenerateAction: invoice={} schedules={} (idempotent={})",
                    invoiceId, schedules.size(), alreadyExists);
        }

        String afterJson = toJson(schedules);
        String details = buildResultDetails(invoiceId, force, alreadyExists, pendingCount, schedules.size());

        return RepairActionResult.builder()
                .repairKey(getRepairKey())
                .success(true)
                .dryRun(false)
                .afterSnapshotJson(afterJson)
                .details(details)
                .evaluatedAt(LocalDateTime.now())
                .build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String buildDryRunDetails(Long invoiceId, Invoice invoice, boolean force,
                                      boolean alreadyExists, long pendingCount) {
        if (force) {
            return "DRY-RUN [force]: would delete " + pendingCount
                    + " PENDING schedule rows and regenerate (catch-up) for invoice " + invoiceId
                    + " (period " + invoice.getPeriodStart().toLocalDate()
                    + " → " + invoice.getPeriodEnd().toLocalDate() + ")";
        }
        return alreadyExists
                ? "DRY-RUN: schedule already exists for invoice " + invoiceId + " — no rows would be created"
                : "DRY-RUN: schedule would be generated for PAID invoice " + invoiceId
                  + " (period " + invoice.getPeriodStart().toLocalDate()
                  + " → " + invoice.getPeriodEnd().toLocalDate() + ")";
    }

    private String buildResultDetails(Long invoiceId, boolean force,
                                      boolean alreadyExisted, long deletedPending, int created) {
        if (force) {
            return "Force catch-up: deleted " + deletedPending + " PENDING row(s) and created "
                    + created + " catch-up schedule rows for invoice " + invoiceId;
        }
        return (alreadyExisted ? "Idempotent: existing" : "Created") + " "
                + created + " revenue recognition schedule row(s) for invoice " + invoiceId;
    }

    private Long parseId(String id) {
        try { return Long.parseLong(id); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid invoice id: " + id); }
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }
}
