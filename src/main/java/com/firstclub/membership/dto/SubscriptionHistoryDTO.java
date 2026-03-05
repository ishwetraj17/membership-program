package com.firstclub.membership.dto;

import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.entity.SubscriptionHistory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for SubscriptionHistory audit entries.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionHistoryDTO {
    private Long id;
    private Long subscriptionId;
    private SubscriptionHistory.EventType eventType;
    private Long oldPlanId;
    private Long newPlanId;
    private Subscription.SubscriptionStatus oldStatus;
    private Subscription.SubscriptionStatus newStatus;
    private String reason;
    private Long changedByUserId;
    private LocalDateTime changedAt;
}
