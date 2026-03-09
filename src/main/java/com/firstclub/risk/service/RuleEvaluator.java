package com.firstclub.risk.service;

import com.firstclub.risk.entity.RiskRule;

/**
 * Strategy interface for evaluating a single risk rule against a payment context.
 *
 * <p>Implementations are registered as Spring beans. {@link RiskRuleService} discovers
 * all beans implementing this interface and dispatches by {@link #ruleType()}.
 *
 * <p>Supported built-in types: USER_VELOCITY_LAST_HOUR, IP_VELOCITY_LAST_10_MIN,
 * BLOCKLIST_IP, DEVICE_REUSE.
 */
public interface RuleEvaluator {

    /** Unique rule type string that this evaluator handles. */
    String ruleType();

    /**
     * Returns {@code true} when the rule fires for the given context.
     * Must not throw — return {@code false} on missing context.
     */
    boolean evaluate(RiskRule rule, RiskContext context);

    /**
     * Extracts the risk score contributed by this rule when it matches.
     * Reads {@code "score"} from the rule's {@code config_json}.
     */
    int score(RiskRule rule);
}
