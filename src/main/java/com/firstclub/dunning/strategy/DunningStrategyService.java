package com.firstclub.dunning.strategy;

import com.firstclub.dunning.DunningDecision;
import com.firstclub.dunning.classification.FailureCategory;
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.entity.DunningPolicy;

/**
 * Determines the appropriate {@link DunningDecision} for a failed dunning attempt.
 *
 * <p>Implementations must be stateless and thread-safe; all per-call context is
 * passed as method arguments.
 */
public interface DunningStrategyService {

    /**
     * Decide what action to take after a dunning attempt failed with the given
     * failure category.
     *
     * @param attempt  the attempt that just failed (must already be in FAILED state)
     * @param category the classified failure category for this attempt's error
     * @param policy   the dunning policy governing the subscription's retry schedule
     * @return the decision — never {@code null}
     */
    DunningDecision decide(DunningAttempt attempt, FailureCategory category, DunningPolicy policy);
}
