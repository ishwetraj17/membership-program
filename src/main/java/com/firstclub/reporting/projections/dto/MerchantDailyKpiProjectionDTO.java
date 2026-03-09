package com.firstclub.reporting.projections.dto;

import com.firstclub.reporting.projections.entity.MerchantDailyKpiProjection;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Response DTO for a single {@link MerchantDailyKpiProjection} row. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantDailyKpiProjectionDTO {

    private Long        merchantId;
    private LocalDate   businessDate;
    private int         invoicesCreated;
    private int         invoicesPaid;
    private int         paymentsCaptured;
    private int         refundsCompleted;
    private int         disputesOpened;
    private BigDecimal  revenueRecognized;
    private LocalDateTime updatedAt;

    public static MerchantDailyKpiProjectionDTO from(MerchantDailyKpiProjection p) {
        return MerchantDailyKpiProjectionDTO.builder()
                .merchantId(p.getMerchantId())
                .businessDate(p.getBusinessDate())
                .invoicesCreated(p.getInvoicesCreated())
                .invoicesPaid(p.getInvoicesPaid())
                .paymentsCaptured(p.getPaymentsCaptured())
                .refundsCompleted(p.getRefundsCompleted())
                .disputesOpened(p.getDisputesOpened())
                .revenueRecognized(p.getRevenueRecognized())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
