package com.firstclub.membership.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for subscription plan-change operations (upgrade / downgrade).
 * Replaces the raw Map&lt;String, Long&gt; request body that previously
 * gave no compile-time safety and silently passed null when the key was absent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanChangeRequestDTO {

    @NotNull(message = "newPlanId is required")
    @Positive(message = "newPlanId must be a positive number")
    private Long newPlanId;
}
