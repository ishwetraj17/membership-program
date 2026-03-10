package com.firstclub.risk.scoring;

import com.firstclub.risk.entity.RiskAction;

import java.util.List;

/**
 * Human-readable explanation of the most recent risk decision for a payment intent.
 *
 * @param paymentIntentId   the evaluated payment intent
 * @param decision          final action taken (ALLOW / CHALLENGE / BLOCK / REVIEW)
 * @param score             raw risk score at evaluation time
 * @param decayedScore      score after time-decay (may equal score if no decay fields present)
 * @param triggeredRuleIds  IDs of rules that fired and contributed to the score
 * @param matchedRules      raw JSON string from the persisted decision (for full detail)
 * @param explanation       plain-English narrative summarising the decision
 */
public record RiskDecisionExplanation(
        Long paymentIntentId,
        RiskAction decision,
        int score,
        int decayedScore,
        List<Long> triggeredRuleIds,
        String matchedRules,
        String explanation
) {}
