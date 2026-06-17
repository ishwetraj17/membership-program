package com.firstclub.membership.dto;

import com.firstclub.membership.entity.Subscription;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Settings-only update for an existing subscription. Plan changes are intentionally
 * not handled here — use the dedicated upgrade / downgrade endpoints, which apply the
 * correct direction validation, pro-ration and date anchoring.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionUpdateDTO {

    private Boolean autoRenewal;

    private Subscription.SubscriptionStatus status;

    private String reason; // For cancellation or status changes
}