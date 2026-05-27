package com.firstclub.membership.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Result of evaluating a user's tier eligibility.
 *
 * Contains both the computed recommendation and the underlying metrics so
 * the caller can explain the decision to the user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TierEligibilityResult {

    private Long userId;

    /** The highest tier the user currently qualifies for. */
    private String eligibleTierName;

    /** Order count observed in the evaluation window. */
    private Integer orderCount;

    /** Total spend (INR) observed in the evaluation window. */
    private BigDecimal monthlySpend;

    /** Human-readable explanation of how the result was derived. */
    private String evaluationNote;
}
