package com.firstclub.dunning.service;

import com.firstclub.dunning.entity.DunningAttempt;

import java.util.List;

/**
 * Policy-driven dunning service (v2).
 *
 * <p>Unlike the original {@link DunningService}, this service derives its retry
 * schedule from a {@link com.firstclub.dunning.entity.DunningPolicy} and can
 * attempt a backup payment method when the primary fails.
 */
public interface DunningServiceV2 {

    /**
     * Schedule retry attempts for a failed subscription renewal based on the
     * merchant's effective dunning policy.
     *
     * <p>The number of attempts created is
     * {@code min(policy.maxAttempts, offsets.size())} and obeys the
     * {@code graceDays} window — attempts that would fall outside the window
     * are skipped.
     *
     * @param subscriptionId ID of the SubscriptionV2
     * @param invoiceId      ID of the unpaid invoice
     * @param merchantId     merchant owning the subscription (used to resolve policy)
     */
    void scheduleAttemptsFromPolicy(Long subscriptionId, Long invoiceId, Long merchantId);

    /**
     * Process all overdue v2 policy-driven dunning attempts.
     * Called by the scheduler every polling cycle.
     */
    void processDueV2Attempts();

    /**
     * Return all dunning attempts for a subscription, validating that the
     * subscription belongs to the given merchant.
     */
    List<DunningAttempt> getAttemptsForSubscription(Long merchantId, Long subscriptionId);
}
