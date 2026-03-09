package com.firstclub.payments.dto;

import com.firstclub.payments.entity.FailureCategory;
import com.firstclub.payments.entity.PaymentAttemptStatus;
import lombok.*;

import java.time.LocalDateTime;

/**
 * API response for a single {@link com.firstclub.payments.entity.PaymentAttempt}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentAttemptResponseDTO {

    private Long id;
    private Long paymentIntentId;

    private int attemptNumber;
    private String gatewayName;
    private String gatewayReference;

    private String responseCode;
    private String responseMessage;
    private Long latencyMs;

    private PaymentAttemptStatus status;
    private FailureCategory failureCategory;
    private boolean retriable;

    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    // ── Phase 8: gateway idempotency and observability fields ─────────────────
    private String gatewayIdempotencyKey;
    private String gatewayTransactionId;
    private String requestPayloadHash;
    private String processorNodeId;
    private LocalDateTime startedAt;
}
