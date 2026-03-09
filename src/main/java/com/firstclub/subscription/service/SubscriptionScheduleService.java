package com.firstclub.subscription.service;

import com.firstclub.subscription.dto.SubscriptionScheduleCreateRequestDTO;
import com.firstclub.subscription.dto.SubscriptionScheduleResponseDTO;

import java.util.List;

/**
 * Domain service for managing scheduled actions on subscriptions.
 */
public interface SubscriptionScheduleService {

    /**
     * Creates a new scheduled action for the given subscription.
     *
     * <p>Validates:
     * <ul>
     *   <li>The subscription exists and is not in a terminal state.</li>
     *   <li>{@code effectiveAt} is in the future.</li>
     *   <li>No conflicting {@code SCHEDULED} entry exists at the same timestamp.</li>
     * </ul>
     */
    SubscriptionScheduleResponseDTO createSchedule(Long merchantId, Long subscriptionId,
                                                    SubscriptionScheduleCreateRequestDTO request);

    /**
     * Returns all schedules for a subscription in ascending effective-time order.
     * Validates the subscription belongs to the merchant.
     */
    List<SubscriptionScheduleResponseDTO> listSchedulesForSubscription(Long merchantId, Long subscriptionId);

    /**
     * Cancels a SCHEDULED entry. Executed/failed/already-cancelled entries
     * cannot be cancelled again.
     */
    SubscriptionScheduleResponseDTO cancelSchedule(Long merchantId, Long subscriptionId, Long scheduleId);
}
