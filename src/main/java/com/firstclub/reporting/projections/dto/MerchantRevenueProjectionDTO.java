package com.firstclub.reporting.projections.dto;

import com.firstclub.reporting.projections.entity.MerchantRevenueProjection;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MerchantRevenueProjectionDTO {

    private Long merchantId;
    private long totalRevenueMinor;
    private long totalRefundsMinor;
    private long netRevenueMinor;
    private int activeSubscriptions;
    private int churnedSubscriptions;
    private LocalDateTime updatedAt;

    public static MerchantRevenueProjectionDTO from(MerchantRevenueProjection p) {
        return MerchantRevenueProjectionDTO.builder()
                .merchantId(p.getMerchantId())
                .totalRevenueMinor(p.getTotalRevenueMinor())
                .totalRefundsMinor(p.getTotalRefundsMinor())
                .netRevenueMinor(p.getNetRevenueMinor())
                .activeSubscriptions(p.getActiveSubscriptions())
                .churnedSubscriptions(p.getChurnedSubscriptions())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
