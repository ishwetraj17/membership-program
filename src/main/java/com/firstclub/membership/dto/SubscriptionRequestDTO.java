package com.firstclub.membership.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to create a new membership subscription")
public class SubscriptionRequestDTO {

    @NotNull(message = "User ID is required")
    @Positive(message = "User ID must be positive")
    @Schema(description = "ID of the subscribing user", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @NotNull(message = "Plan ID is required")
    @Positive(message = "Plan ID must be positive")
    @Schema(description = "ID of the plan to subscribe to", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long planId;

    @Builder.Default
    @Schema(description = "Auto-renew on expiry", example = "true", defaultValue = "true")
    private Boolean autoRenewal = true;

    @Schema(description = "Optional creation reason")
    private String reason;
}
