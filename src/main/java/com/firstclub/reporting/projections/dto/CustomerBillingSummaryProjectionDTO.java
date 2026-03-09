package com.firstclub.reporting.projections.dto;

import com.firstclub.reporting.projections.entity.CustomerBillingSummaryProjection;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Response DTO for a single {@link CustomerBillingSummaryProjection} row. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerBillingSummaryProjectionDTO {

    private Long    merchantId;
    private Long    customerId;
    private int     activeSubscriptionsCount;
    private int     unpaidInvoicesCount;
    private BigDecimal totalPaidAmount;
    private BigDecimal totalRefundedAmount;
    private LocalDateTime lastPaymentAt;
    private LocalDateTime updatedAt;

    public static CustomerBillingSummaryProjectionDTO from(CustomerBillingSummaryProjection p) {
        return CustomerBillingSummaryProjectionDTO.builder()
                .merchantId(p.getMerchantId())
                .customerId(p.getCustomerId())
                .activeSubscriptionsCount(p.getActiveSubscriptionsCount())
                .unpaidInvoicesCount(p.getUnpaidInvoicesCount())
                .totalPaidAmount(p.getTotalPaidAmount())
                .totalRefundedAmount(p.getTotalRefundedAmount())
                .lastPaymentAt(p.getLastPaymentAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
