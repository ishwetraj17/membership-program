package com.firstclub.reporting.ops.dto;

import com.firstclub.reporting.ops.entity.PaymentSummaryProjection;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Value
@Builder
public class PaymentSummaryProjectionDTO {

    Long         merchantId;
    Long         paymentIntentId;
    Long         customerId;
    Long         invoiceId;
    String       status;
    BigDecimal   capturedAmount;
    BigDecimal   refundedAmount;
    BigDecimal   disputedAmount;
    int          attemptCount;
    String       lastGateway;
    String       lastFailureCategory;
    LocalDateTime updatedAt;

    public static PaymentSummaryProjectionDTO from(PaymentSummaryProjection p) {
        return PaymentSummaryProjectionDTO.builder()
                .merchantId(p.getMerchantId())
                .paymentIntentId(p.getPaymentIntentId())
                .customerId(p.getCustomerId())
                .invoiceId(p.getInvoiceId())
                .status(p.getStatus())
                .capturedAmount(p.getCapturedAmount())
                .refundedAmount(p.getRefundedAmount())
                .disputedAmount(p.getDisputedAmount())
                .attemptCount(p.getAttemptCount())
                .lastGateway(p.getLastGateway())
                .lastFailureCategory(p.getLastFailureCategory())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
