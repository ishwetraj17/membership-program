package com.firstclub.risk.dto;

import com.firstclub.risk.entity.RiskAction;
import com.firstclub.risk.entity.RiskRule;

import java.time.LocalDateTime;

public record RiskRuleResponseDTO(
        Long id,
        Long merchantId,
        String ruleCode,
        String ruleType,
        String configJson,
        RiskAction action,
        boolean active,
        int priority,
        LocalDateTime createdAt
) {
    public static RiskRuleResponseDTO from(RiskRule r) {
        return new RiskRuleResponseDTO(
                r.getId(), r.getMerchantId(), r.getRuleCode(), r.getRuleType(),
                r.getConfigJson(), r.getAction(), r.isActive(), r.getPriority(), r.getCreatedAt());
    }
}
