package com.firstclub.platform.repair.actions;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.InvoiceTotalService;
import com.firstclub.platform.repair.RepairAction;
import com.firstclub.platform.repair.RepairActionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recomputes all monetary total columns of an invoice from its invoice lines.
 *
 * <p><b>What changes:</b> {@code subtotal}, {@code discountTotal},
 * {@code creditTotal}, {@code taxTotal}, {@code grandTotal} on the
 * {@code invoices} row.
 *
 * <p><b>What is never changed:</b> {@code id}, {@code invoiceNumber},
 * {@code status}, {@code userId}, {@code merchantId}, {@code currency},
 * {@code periodStart}, {@code periodEnd}, {@code subscriptionId}.
 *
 * <p><b>Dry-run:</b> supported — computes the new totals but does not save.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvoiceRecomputeAction implements RepairAction {

    private final InvoiceRepository  invoiceRepository;
    private final InvoiceTotalService invoiceTotalService;
    private final ObjectMapper        objectMapper;

    @Override
    public String getRepairKey() { return "repair.invoice.recompute_totals"; }

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

        String beforeJson = snapshot(invoice);

        if (context.dryRun()) {
            // Compute without saving — clone totals here for the dry-run snapshot
            Invoice copy = invoiceTotalService.recomputeTotals(invoice);
            String dryRunAfterJson = snapshot(copy);
            log.info("[DRY-RUN] InvoiceRecomputeAction invoice={} would update totals", invoiceId);
            return RepairActionResult.builder()
                    .repairKey(getRepairKey())
                    .success(true)
                    .dryRun(true)
                    .beforeSnapshotJson(beforeJson)
                    .afterSnapshotJson(dryRunAfterJson)
                    .details("DRY-RUN: invoice " + invoiceId + " totals would be recomputed from lines")
                    .evaluatedAt(LocalDateTime.now())
                    .build();
        }

        Invoice updated = invoiceTotalService.recomputeTotals(invoice);
        invoiceRepository.save(updated);
        String afterJson = snapshot(updated);
        log.info("InvoiceRecomputeAction: invoice={} totals recomputed", invoiceId);

        return RepairActionResult.builder()
                .repairKey(getRepairKey())
                .success(true)
                .dryRun(false)
                .beforeSnapshotJson(beforeJson)
                .afterSnapshotJson(afterJson)
                .details("Invoice " + invoiceId + " totals recomputed from lines")
                .evaluatedAt(LocalDateTime.now())
                .build();
    }

    private Long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid invoice id: " + id);
        }
    }

    private String snapshot(Invoice inv) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", inv.getId());
            m.put("invoiceNumber", inv.getInvoiceNumber());
            m.put("status", inv.getStatus());
            m.put("subtotal", inv.getSubtotal());
            m.put("discountTotal", inv.getDiscountTotal());
            m.put("creditTotal", inv.getCreditTotal());
            m.put("taxTotal", inv.getTaxTotal());
            m.put("grandTotal", inv.getGrandTotal());
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }
}
