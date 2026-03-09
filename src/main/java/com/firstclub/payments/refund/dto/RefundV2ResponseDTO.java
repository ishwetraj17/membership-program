package com.firstclub.payments.refund.dto;

import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.refund.entity.RefundV2Status;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * API response for a single {@code refunds_v2} row, enriched with the
 * post-refund payment state to let callers know the new amounts in one call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundV2ResponseDTO {

    private Long id;

    private Long merchantId;

    private Long paymentId;

    /** Null when the refund was not linked to a specific invoice. */
    private Long invoiceId;

    private BigDecimal amount;

    private String reasonCode;

    private RefundV2Status status;

    private String refundReference;

    /** Idempotency fingerprint that was stored with this refund (Phase 15). */
    private String requestFingerprint;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    // ── Post-refund payment snapshot ────────────────────────────────────────

    /**
     * Remaining refundable amount after this refund has been applied
     * ({@code capturedAmount - newRefundedAmount - disputedAmount}).
     */
    private BigDecimal refundableAmountAfter;

    /** Payment status as it stands after this refund has been applied. */
    private PaymentStatus paymentStatusAfter;
}
