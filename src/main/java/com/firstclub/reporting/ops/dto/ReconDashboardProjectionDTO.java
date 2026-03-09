package com.firstclub.reporting.ops.dto;

import com.firstclub.reporting.ops.entity.ReconDashboardProjection;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Value
@Builder
public class ReconDashboardProjectionDTO {

    Long         id;
    Long         merchantId;
    LocalDate    businessDate;
    int          layer2Open;
    int          layer3Open;
    int          layer4Open;
    int          resolvedCount;
    BigDecimal   unresolvedAmount;
    LocalDateTime updatedAt;

    public static ReconDashboardProjectionDTO from(ReconDashboardProjection p) {
        return ReconDashboardProjectionDTO.builder()
                .id(p.getId())
                .merchantId(p.getMerchantId())
                .businessDate(p.getBusinessDate())
                .layer2Open(p.getLayer2Open())
                .layer3Open(p.getLayer3Open())
                .layer4Open(p.getLayer4Open())
                .resolvedCount(p.getResolvedCount())
                .unresolvedAmount(p.getUnresolvedAmount())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
