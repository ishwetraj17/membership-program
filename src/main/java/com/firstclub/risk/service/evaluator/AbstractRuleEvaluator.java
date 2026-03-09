package com.firstclub.risk.service.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.risk.entity.RiskRule;
import com.firstclub.risk.service.RuleEvaluator;

/**
 * Base class for rule evaluators, providing common config_json parsing helpers.
 */
public abstract class AbstractRuleEvaluator implements RuleEvaluator {

    protected final ObjectMapper objectMapper;

    protected AbstractRuleEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public int score(RiskRule rule) {
        return parseIntField(rule.getConfigJson(), "score", 0);
    }

    protected int parseIntField(String configJson, String field, int defaultValue) {
        try {
            return objectMapper.readTree(configJson).path(field).asInt(defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
