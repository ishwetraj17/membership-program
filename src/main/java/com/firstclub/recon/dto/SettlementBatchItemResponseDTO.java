package com.firstclub.recon.dto;

import com.firstclub.recon.entity.SettlementBatchItem;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Value
@Builder
public class SettlementBatchItemResponseDTO {
    Long          id;
    Long          batchId;
    Long          paymentId;
    BigDecimal    amount;
    BigDecimal    feeAmount;
    BigDecimal    reserveAmount;
    BigDecimal    netAmount;
    LocalDateTime createdAt;

    public static SettlementBatchItemResponseDTO from(SettlementBatchItem item) {
        return SettlementBatchItemResponseDTO.builder()
                .id(item.getId())
                .batchId(item.getBatchId())
                .paymentId(item.getPaymentId())
                .amount(item.getAmount())
                .feeAmount(item.getFeeAmount())
                .reserveAmount(item.getReserveAmount())
                .netAmount(item.getNetAmount())
                .createdAt(item.getCreatedAt())
                .build();
    }
}
