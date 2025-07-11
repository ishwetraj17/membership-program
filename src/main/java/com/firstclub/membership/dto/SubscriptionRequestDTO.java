package com.firstclub.membership.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.NotNull;

/**
 * DTO for creating new subscriptions
 * 
 * Simple request object for subscription creation.
 * Validates that user and plan IDs are provided.
 * 
 * Implemented by Shwet Raj
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionRequestDTO {

    @NotNull(message = "User ID is required")
    private Long userId;

    @NotNull(message = "Plan ID is required")
    private Long planId;

    // Default to true if not specified
    @Builder.Default
    private Boolean autoRenewal = true;
}