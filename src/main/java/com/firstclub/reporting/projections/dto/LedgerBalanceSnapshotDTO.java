package com.firstclub.reporting.projections.dto;

import com.firstclub.reporting.projections.entity.LedgerBalanceSnapshot;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Response DTO for a single {@link LedgerBalanceSnapshot} row. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerBalanceSnapshotDTO {

    private Long        id;
    private Long        merchantId;
    private Long        accountId;
    private LocalDate   snapshotDate;
    private BigDecimal  balance;
    private LocalDateTime createdAt;

    public static LedgerBalanceSnapshotDTO from(LedgerBalanceSnapshot s) {
        return LedgerBalanceSnapshotDTO.builder()
                .id(s.getId())
                .merchantId(s.getMerchantId())
                .accountId(s.getAccountId())
                .snapshotDate(s.getSnapshotDate())
                .balance(s.getBalance())
                .createdAt(s.getCreatedAt())
                .build();
    }
}
