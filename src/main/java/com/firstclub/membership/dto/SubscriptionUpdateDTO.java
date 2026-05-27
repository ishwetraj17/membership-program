package com.firstclub.membership.dto;

import com.firstclub.membership.entity.Subscription;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionUpdateDTO {

    private Boolean autoRenewal;
    
    @Min(value = 1, message = "Plan ID must be positive")
    private Long newPlanId;
    
    private Subscription.SubscriptionStatus status;
    
    private String reason; // For cancellation or status changes
}