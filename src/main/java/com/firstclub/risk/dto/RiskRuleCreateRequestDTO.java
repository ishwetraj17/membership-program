package com.firstclub.risk.dto;

import com.firstclub.risk.entity.RiskAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for both creating ({@code POST}) and updating ({@code PUT}) a risk rule.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskRuleCreateRequestDTO {

    /** null = platform-wide rule. */
    private Long merchantId;

    @NotBlank(message = "ruleCode is required")
    private String ruleCode;

    /**
     * Must match a registered evaluator type.
     * Supported: USER_VELOCITY_LAST_HOUR, IP_VELOCITY_LAST_10_MIN, BLOCKLIST_IP, DEVICE_REUSE.
     */
    @NotBlank(message = "ruleType is required")
    private String ruleType;

    /**
     * JSON config consumed by the evaluator.
     * Minimum fields: {@code {"threshold": N, "score": N}}.
     * BLOCKLIST_IP only requires {@code {"score": N}}.
     */
    @NotBlank(message = "configJson is required")
    private String configJson;

    @NotNull(message = "action is required")
    private RiskAction action;

    @Builder.Default
    private boolean active = true;

    @Positive(message = "priority must be > 0")
    private int priority;
}
