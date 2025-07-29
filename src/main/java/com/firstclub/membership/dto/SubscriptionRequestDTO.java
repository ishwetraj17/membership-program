package com.firstclub.membership.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO for creating new subscriptions
 * 
 * Enhanced request object with comprehensive validation.
 * Ensures proper subscription creation with valid user and plan references.
 * 
 * Implemented by Shwet Raj
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request object for creating a new membership subscription")
public class SubscriptionRequestDTO {

    @NotNull(message = "User ID is required")
    @Positive(message = "User ID must be positive")
    @Schema(description = "ID of the user who will own this subscription", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @NotNull(message = "Plan ID is required")
    @Positive(message = "Plan ID must be positive")
    @Schema(description = "ID of the membership plan to subscribe to", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long planId;

    // Default to true if not specified
    @Builder.Default
    @Schema(description = "Whether the subscription should auto-renew when it expires", example = "true", defaultValue = "true")
    private Boolean autoRenewal = true;
    
    @Schema(description = "Optional reason for subscription creation", example = "User upgrade from trial")
    private String reason;
    
    /**
     * Helper method to set user ID (used by user-specific endpoints)
     */
    public void setUserId(Long userId) {
        this.userId = userId;
    }
}