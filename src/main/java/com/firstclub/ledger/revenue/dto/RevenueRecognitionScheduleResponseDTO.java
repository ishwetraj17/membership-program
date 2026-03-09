package com.firstclub.ledger.revenue.dto;

import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueRecognitionScheduleResponseDTO {
    private Long id;
    private Long merchantId;
    private Long subscriptionId;
    private Long invoiceId;
    private LocalDate recognitionDate;
    private BigDecimal amount;
    private String currency;
    private RevenueRecognitionStatus status;
    private Long ledgerEntryId;

    /** SHA-256 fingerprint of the generation inputs (Phase 14). */
    private String generationFingerprint;

    /** Batch run ID that posted this row (null if not yet posted, Phase 14). */
    private Long postingRunId;

    /** True when generated via explicit repair / force-regeneration (Phase 14). */
    private boolean catchUpRun;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
