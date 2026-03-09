package com.firstclub.risk.dto;

import com.firstclub.risk.entity.RiskAction;
import com.firstclub.risk.entity.RiskDecision;

import java.time.LocalDateTime;

public record RiskDecisionResponseDTO(
        Long id,
        Long merchantId,
        Long paymentIntentId,
        Long customerId,
        int score,
        RiskAction decision,
        String matchedRulesJson,
        LocalDateTime createdAt
) {
    public static RiskDecisionResponseDTO from(RiskDecision d) {
        return new RiskDecisionResponseDTO(
                d.getId(), d.getMerchantId(), d.getPaymentIntentId(), d.getCustomerId(),
                d.getScore(), d.getDecision(), d.getMatchedRulesJson(), d.getCreatedAt());
    }
}
