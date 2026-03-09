package com.firstclub.dunning.service;

import com.firstclub.dunning.dto.SubscriptionPaymentPreferenceRequestDTO;
import com.firstclub.dunning.dto.SubscriptionPaymentPreferenceResponseDTO;

/**
 * Manages payment method preferences for individual subscriptions.
 *
 * <p>Each subscription may have a primary and an optional backup payment method.
 * DunningServiceV2 consults these preferences when scheduling retries.
 */
public interface SubscriptionPaymentPreferenceService {

    /**
     * Create or replace the payment preferences for a subscription.
     *
     * <p>Validates that both payment methods belong to the subscription's customer
     * within the merchant namespace.  Primary and backup must not be identical.
     */
    SubscriptionPaymentPreferenceResponseDTO setPaymentPreferences(
            Long merchantId, Long subscriptionId, SubscriptionPaymentPreferenceRequestDTO request);

    /**
     * Return the current preferences for a subscription.
     *
     * @throws com.firstclub.membership.exception.MembershipException 404 if not found
     */
    SubscriptionPaymentPreferenceResponseDTO getPreferencesForSubscription(
            Long merchantId, Long subscriptionId);
}
