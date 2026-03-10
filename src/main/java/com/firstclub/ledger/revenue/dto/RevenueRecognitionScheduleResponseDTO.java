package com.firstclub.ledger.revenue.dto;

import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.guard.GuardDecision;
import com.firstclub.ledger.revenue.guard.RecognitionPolicyCode;
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

    // ── Phase 15: guard and minor-unit fields ──────────────────────────────────

    /** Expected recognition amount in minor currency units (e.g. paise). */
    private Long expectedAmountMinor;

    /** Actual recognized amount in minor units — set when POSTED. */
    private Long recognizedAmountMinor;

    /** Rounding remainder absorbed by the last row, in minor units. */
    private Long roundingAdjustmentMinor;

    /** Recognition policy applied by the guard (e.g. RECOGNIZE, SKIP, HALT). */
    private RecognitionPolicyCode policyCode;

    /** Guard decision (e.g. ALLOW, BLOCK, DEFER, FLAG, HALT). */
    private GuardDecision guardDecision;

    /** Human-readable explanation for the guard decision. */
    private String guardReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
