package com.firstclub.membership.service;

import com.firstclub.membership.dto.TierEligibilityResult;

public interface TierEvaluationService {

    /**
     * Evaluates the highest membership tier a user is currently eligible for
     * based on their order history over the configured evaluation window.
     */
    TierEligibilityResult evaluateEligibleTier(Long userId);

    /**
     * Returns true if the user meets the eligibility criteria for the given tier.
     */
    boolean isEligibleForTier(Long userId, String tierName);
}
