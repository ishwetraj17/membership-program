package com.firstclub.billing.dto;

import com.firstclub.billing.model.InvoiceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceDTO {

    private Long id;
    private Long userId;
    private Long subscriptionId;
    private InvoiceStatus status;
    private String currency;
    private BigDecimal totalAmount;
    private LocalDateTime dueDate;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<InvoiceLineDTO> lines;

    // ── Phase 8: merchant scope + total breakdown ────────────────────────────
    private Long merchantId;
    private String invoiceNumber;
    private BigDecimal subtotal;
    private BigDecimal discountTotal;
    private BigDecimal creditTotal;
    private BigDecimal taxTotal;
    private BigDecimal grandTotal;
}
