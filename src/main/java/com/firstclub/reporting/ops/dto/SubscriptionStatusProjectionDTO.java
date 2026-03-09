package com.firstclub.reporting.ops.dto;

import com.firstclub.reporting.ops.entity.SubscriptionStatusProjection;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class SubscriptionStatusProjectionDTO {

    Long         merchantId;
    Long         subscriptionId;
    Long         customerId;
    String       status;
    LocalDateTime nextBillingAt;
    String       dunningState;
    int          unpaidInvoiceCount;
    String       lastPaymentStatus;
    LocalDateTime updatedAt;

    public static SubscriptionStatusProjectionDTO from(SubscriptionStatusProjection p) {
        return SubscriptionStatusProjectionDTO.builder()
                .merchantId(p.getMerchantId())
                .subscriptionId(p.getSubscriptionId())
                .customerId(p.getCustomerId())
                .status(p.getStatus())
                .nextBillingAt(p.getNextBillingAt())
                .dunningState(p.getDunningState())
                .unpaidInvoiceCount(p.getUnpaidInvoiceCount())
                .lastPaymentStatus(p.getLastPaymentStatus())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
