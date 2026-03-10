package com.firstclub.risk.dto;

import com.firstclub.risk.entity.ManualReviewCase;
import com.firstclub.risk.entity.ReviewCaseStatus;

import java.time.LocalDateTime;

public record ManualReviewCaseResponseDTO(
        Long id,
        Long merchantId,
        Long paymentIntentId,
        Long customerId,
        ReviewCaseStatus status,
        Long assignedTo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        // Phase 18 fields
        LocalDateTime slaDueAt,
        LocalDateTime escalatedAt,
        String decisionReason,
        Long closedBy,
        LocalDateTime closedAt
) {
    public static ManualReviewCaseResponseDTO from(ManualReviewCase c) {
        return new ManualReviewCaseResponseDTO(
                c.getId(), c.getMerchantId(), c.getPaymentIntentId(), c.getCustomerId(),
                c.getStatus(), c.getAssignedTo(), c.getCreatedAt(), c.getUpdatedAt(),
                c.getSlaDueAt(), c.getEscalatedAt(), c.getDecisionReason(),
                c.getClosedBy(), c.getClosedAt());
    }
}
