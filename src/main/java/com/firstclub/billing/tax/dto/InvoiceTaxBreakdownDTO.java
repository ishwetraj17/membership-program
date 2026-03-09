package com.firstclub.billing.tax.dto;

import com.firstclub.billing.model.InvoiceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Tax breakdown view of an invoice.
 *
 * <p>Policy: tax is computed on {@code taxableBase = subtotal - discountTotal}
 * (after discount, before credit application).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceTaxBreakdownDTO {

    private Long invoiceId;
    private String invoiceNumber;
    private Long merchantId;
    private InvoiceStatus status;
    private String currency;

    // ── Totals ────────────────────────────────────────────────────────────────
    private BigDecimal subtotal;
    private BigDecimal discountTotal;

    /** taxableBase = subtotal - discountTotal */
    private BigDecimal taxableBase;

    private BigDecimal cgst;
    private BigDecimal sgst;
    private BigDecimal igst;

    /** Sum of all GST lines = cgst + sgst + igst */
    private BigDecimal taxTotal;

    private BigDecimal creditTotal;
    private BigDecimal grandTotal;

    // ── Regime ───────────────────────────────────────────────────────────────
    /** true = intra-state (CGST+SGST); false = inter-state (IGST). */
    private boolean intraState;
    private boolean taxExempt;

    private LocalDateTime calculatedAt;

    // ── Individual tax lines ──────────────────────────────────────────────────
    private List<TaxLineDTO> taxLines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaxLineDTO {
        private Long lineId;
        private String lineType;
        private String description;
        private BigDecimal amount;
    }
}
