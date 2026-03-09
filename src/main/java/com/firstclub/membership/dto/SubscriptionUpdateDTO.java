package com.firstclub.membership.dto;

import com.firstclub.membership.entity.Subscription;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * DTO for updating subscription settings
 * 
 * Supports updating auto-renewal, plan changes, and status changes.
 * Enhanced to provide comprehensive subscription management.
 * 
 * Implemented by Shwet Raj
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionUpdateDTO {

    private Boolean autoRenewal;

    @Positive(message = "Plan ID must be a positive number")
    private Long newPlanId;

    private Subscription.SubscriptionStatus status;

    @Size(max = 500, message = "Reason must not exceed 500 characters")
    private String reason; // For cancellation or status changes
}