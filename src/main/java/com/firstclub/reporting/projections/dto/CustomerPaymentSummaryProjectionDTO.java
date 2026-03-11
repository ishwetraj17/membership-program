package com.firstclub.reporting.projections.dto;

import com.firstclub.reporting.projections.entity.CustomerPaymentSummaryProjection;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CustomerPaymentSummaryProjectionDTO {

    private Long merchantId;
    private Long customerId;
    private long totalChargedMinor;
    private long totalRefundedMinor;
    private int successfulPayments;
    private int failedPayments;
    private LocalDateTime lastPaymentAt;
    private LocalDateTime updatedAt;

    public static CustomerPaymentSummaryProjectionDTO from(CustomerPaymentSummaryProjection p) {
        return CustomerPaymentSummaryProjectionDTO.builder()
                .merchantId(p.getMerchantId())
                .customerId(p.getCustomerId())
                .totalChargedMinor(p.getTotalChargedMinor())
                .totalRefundedMinor(p.getTotalRefundedMinor())
                .successfulPayments(p.getSuccessfulPayments())
                .failedPayments(p.getFailedPayments())
                .lastPaymentAt(p.getLastPaymentAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
