package com.firstclub.risk.service.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.risk.entity.RiskRule;
import com.firstclub.risk.repository.RiskEventRepository;
import com.firstclub.risk.service.RiskContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Fires when the customer has exceeded the configured number of payment attempts
 * in the past hour.
 *
 * config_json: {@code {"threshold": 5, "score": 30}}
 */
@Component
public class UserVelocityEvaluator extends AbstractRuleEvaluator {

    private final RiskEventRepository eventRepository;

    public UserVelocityEvaluator(ObjectMapper objectMapper,
                                  RiskEventRepository eventRepository) {
        super(objectMapper);
        this.eventRepository = eventRepository;
    }

    @Override
    public String ruleType() {
        return "USER_VELOCITY_LAST_HOUR";
    }

    @Override
    public boolean evaluate(RiskRule rule, RiskContext context) {
        if (context.customerId() == null) return false;
        int threshold = parseIntField(rule.getConfigJson(), "threshold", 5);
        long count = eventRepository.countPaymentAttemptsByUserSince(
                context.customerId(), LocalDateTime.now().minusHours(1));
        return count >= threshold;
    }
}
