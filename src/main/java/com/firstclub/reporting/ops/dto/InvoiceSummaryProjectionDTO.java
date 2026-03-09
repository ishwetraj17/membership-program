package com.firstclub.reporting.ops.dto;

import com.firstclub.reporting.ops.entity.InvoiceSummaryProjection;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Value
@Builder
public class InvoiceSummaryProjectionDTO {

    Long         merchantId;
    Long         invoiceId;
    String       invoiceNumber;
    Long         customerId;
    String       status;
    BigDecimal   subtotal;
    BigDecimal   taxTotal;
    BigDecimal   grandTotal;
    LocalDateTime paidAt;
    boolean      overdueFlag;
    LocalDateTime updatedAt;

    public static InvoiceSummaryProjectionDTO from(InvoiceSummaryProjection p) {
        return InvoiceSummaryProjectionDTO.builder()
                .merchantId(p.getMerchantId())
                .invoiceId(p.getInvoiceId())
                .invoiceNumber(p.getInvoiceNumber())
                .customerId(p.getCustomerId())
                .status(p.getStatus())
                .subtotal(p.getSubtotal())
                .taxTotal(p.getTaxTotal())
                .grandTotal(p.getGrandTotal())
                .paidAt(p.getPaidAt())
                .overdueFlag(p.isOverdueFlag())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
