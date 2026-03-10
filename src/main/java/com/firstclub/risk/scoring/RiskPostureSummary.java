package com.firstclub.risk.scoring;

import com.firstclub.risk.entity.RiskAction;

/**
 * Snapshot of a merchant's recent risk posture based on their last N decisions.
 *
 * @param merchantId          the merchant
 * @param recentDecisionCount total decisions examined (up to the look-back window)
 * @param blockCount          how many resulted in BLOCK
 * @param reviewCount         how many resulted in REVIEW
 * @param challengeCount      how many resulted in CHALLENGE
 * @param allowCount          how many resulted in ALLOW
 * @param avgScore            mean raw score across examined decisions
 * @param dominantAction      the action that appeared most frequently
 */
public record RiskPostureSummary(
        Long merchantId,
        int recentDecisionCount,
        int blockCount,
        int reviewCount,
        int challengeCount,
        int allowCount,
        int avgScore,
        RiskAction dominantAction
) {}
