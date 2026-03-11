package com.firstclub.reporting.projections.dto;

import com.firstclub.reporting.projections.entity.LedgerBalanceProjection;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class LedgerBalanceProjectionDTO {

    private Long merchantId;
    private Long userId;
    private long totalCreditsMinor;
    private long totalDebitsMinor;
    private long netBalanceMinor;
    private int entryCount;
    private LocalDateTime lastEntryAt;
    private LocalDateTime updatedAt;

    public static LedgerBalanceProjectionDTO from(LedgerBalanceProjection p) {
        return LedgerBalanceProjectionDTO.builder()
                .merchantId(p.getMerchantId())
                .userId(p.getUserId())
                .totalCreditsMinor(p.getTotalCreditsMinor())
                .totalDebitsMinor(p.getTotalDebitsMinor())
                .netBalanceMinor(p.getNetBalanceMinor())
                .entryCount(p.getEntryCount())
                .lastEntryAt(p.getLastEntryAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
