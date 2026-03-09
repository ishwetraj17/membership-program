package com.firstclub.subscription.dto;

import com.firstclub.subscription.entity.SubscriptionStatusV2;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for a {@link com.firstclub.subscription.entity.SubscriptionV2}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionResponseDTO {

    private Long id;

    private Long merchantId;
    private Long customerId;
    private Long productId;
    private Long priceId;
    private Long priceVersionId;

    private SubscriptionStatusV2 status;

    private LocalDateTime billingAnchorAt;
    private LocalDateTime currentPeriodStart;
    private LocalDateTime currentPeriodEnd;
    private LocalDateTime nextBillingAt;

    private boolean cancelAtPeriodEnd;
    private LocalDateTime cancelledAt;

    private LocalDateTime pauseStartsAt;
    private LocalDateTime pauseEndsAt;

    private LocalDateTime trialEndsAt;

    private String metadataJson;

    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
