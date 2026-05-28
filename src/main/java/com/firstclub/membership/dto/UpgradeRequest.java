package com.firstclub.membership.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to upgrade or downgrade a subscription plan")
public class UpgradeRequest {

    @NotNull(message = "New plan ID is required")
    @Positive(message = "New plan ID must be positive")
    @Schema(description = "ID of the target plan", example = "4", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long newPlanId;
}
