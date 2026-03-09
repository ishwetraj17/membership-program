package com.firstclub.risk.service;

import com.firstclub.risk.entity.RiskRule;

import java.util.List;

/**
 * Aggregate result of evaluating all active rules for a single payment context.
 *
 * @param matchedRules Rules that fired — used to derive the final {@link com.firstclub.risk.entity.RiskAction}.
 * @param totalScore   Sum of {@code score} values from all matched rules' config_json.
 */
public record RiskEvaluationResult(List<RiskRule> matchedRules, int totalScore) {}
