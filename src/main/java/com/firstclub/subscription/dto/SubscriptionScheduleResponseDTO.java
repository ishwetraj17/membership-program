package com.firstclub.subscription.dto;

import com.firstclub.subscription.entity.SubscriptionScheduledAction;
import com.firstclub.subscription.entity.SubscriptionScheduleStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for a {@link com.firstclub.subscription.entity.SubscriptionSchedule}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionScheduleResponseDTO {

    private Long id;
    private Long subscriptionId;

    private SubscriptionScheduledAction scheduledAction;
    private LocalDateTime effectiveAt;
    private String payloadJson;
    private SubscriptionScheduleStatus status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
