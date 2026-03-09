package com.firstclub.subscription.service;

import com.firstclub.subscription.dto.SubscriptionCreateRequestDTO;
import com.firstclub.subscription.dto.SubscriptionResponseDTO;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Domain service for managing subscription contracts (v2).
 */
public interface SubscriptionV2Service {

    /**
     * Creates a new subscription for the given merchant/customer/product/price.
     *
     * <p>If the selected price has {@code trialDays > 0}, the subscription is
     * created in {@code TRIALING} status with {@code trialEndsAt} set accordingly.
     * Otherwise, it starts as {@code INCOMPLETE} (awaiting payment confirmation).
     */
    SubscriptionResponseDTO createSubscription(Long merchantId, SubscriptionCreateRequestDTO request);

    /**
     * Returns a specific subscription, enforcing tenant isolation.
     */
    SubscriptionResponseDTO getSubscriptionById(Long merchantId, Long subscriptionId);

    /**
     * Paginated list of subscriptions for a merchant, optionally filtered by status.
     */
    Page<SubscriptionResponseDTO> listSubscriptions(Long merchantId, SubscriptionStatusV2 status, Pageable pageable);

    /**
     * Cancels a subscription immediately or at period end.
     *
     * @param atPeriodEnd if {@code true}, sets {@code cancelAtPeriodEnd = true} and
     *                    leaves the subscription active until the current period ends;
     *                    if {@code false}, cancels immediately.
     */
    SubscriptionResponseDTO cancelSubscription(Long merchantId, Long subscriptionId, boolean atPeriodEnd);

    /**
     * Pauses an active subscription. Only allowed from {@code ACTIVE} state.
     */
    SubscriptionResponseDTO pauseSubscription(Long merchantId, Long subscriptionId);

    /**
     * Resumes a paused subscription. Only allowed from {@code PAUSED} state.
     */
    SubscriptionResponseDTO resumeSubscription(Long merchantId, Long subscriptionId);

    /**
     * Validates the subscription belongs to the given merchant (throws if not).
     */
    void validateSubscriptionBelongsToMerchant(Long merchantId, Long subscriptionId);
}
