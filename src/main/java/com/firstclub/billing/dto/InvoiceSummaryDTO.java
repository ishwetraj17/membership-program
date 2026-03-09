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
public class InvoiceSummaryDTO {

    private Long id;
    private String invoiceNumber;
    private Long merchantId;
    private Long userId;
    private Long subscriptionId;
    private InvoiceStatus status;
    private String currency;

    private BigDecimal subtotal;
    private BigDecimal discountTotal;
    private BigDecimal creditTotal;
    private BigDecimal taxTotal;
    private BigDecimal grandTotal;

    private LocalDateTime dueDate;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private LocalDateTime createdAt;

    private List<InvoiceLineDTO> lines;
}
