package com.firstclub.recon.dto;

import com.firstclub.recon.entity.SettlementBatch;
import com.firstclub.recon.entity.SettlementBatchStatus;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder
public class SettlementBatchResponseDTO {
    Long                              id;
    Long                              merchantId;
    LocalDate                         batchDate;
    String                            gatewayName;
    BigDecimal                        grossAmount;
    BigDecimal                        feeAmount;
    BigDecimal                        reserveAmount;
    BigDecimal                        netAmount;
    String                            currency;
    SettlementBatchStatus             status;
    LocalDateTime                     createdAt;
    List<SettlementBatchItemResponseDTO> items;

    public static SettlementBatchResponseDTO from(SettlementBatch b, List<SettlementBatchItemResponseDTO> items) {
        return SettlementBatchResponseDTO.builder()
                .id(b.getId())
                .merchantId(b.getMerchantId())
                .batchDate(b.getBatchDate())
                .gatewayName(b.getGatewayName())
                .grossAmount(b.getGrossAmount())
                .feeAmount(b.getFeeAmount())
                .reserveAmount(b.getReserveAmount())
                .netAmount(b.getNetAmount())
                .currency(b.getCurrency())
                .status(b.getStatus())
                .createdAt(b.getCreatedAt())
                .items(items)
                .build();
    }
}
