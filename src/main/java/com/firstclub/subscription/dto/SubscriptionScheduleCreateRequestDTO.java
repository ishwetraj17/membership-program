package com.firstclub.subscription.dto;

import com.firstclub.subscription.entity.SubscriptionScheduledAction;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Request body for creating a scheduled action on a subscription.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionScheduleCreateRequestDTO {

    /** Action to schedule. */
    @NotNull
    private SubscriptionScheduledAction scheduledAction;

    /**
     * When the action should take effect. Must be in the future.
     */
    @NotNull
    private LocalDateTime effectiveAt;

    /**
     * Action-specific JSON payload.
     * Examples:
     * <ul>
     *   <li>{@code CHANGE_PRICE}: {@code {"newPriceId": 42}}</li>
     *   <li>{@code PAUSE}: {@code {"pauseEndsAt": "2026-06-01T00:00:00"}}</li>
     *   <li>{@code CANCEL}: {@code {"reason": "customer_request"}}</li>
     * </ul>
     */
    private String payloadJson;
}
