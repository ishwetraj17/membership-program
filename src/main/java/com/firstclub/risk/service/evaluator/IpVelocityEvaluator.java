package com.firstclub.risk.service.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.risk.entity.RiskRule;
import com.firstclub.risk.repository.RiskEventRepository;
import com.firstclub.risk.service.RiskContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Fires when the originating IP has exceeded the configured number of payment attempts
 * in the past 10 minutes.
 *
 * config_json: {@code {"threshold": 3, "score": 50}}
 */
@Component
public class IpVelocityEvaluator extends AbstractRuleEvaluator {

    private final RiskEventRepository eventRepository;

    public IpVelocityEvaluator(ObjectMapper objectMapper,
                                RiskEventRepository eventRepository) {
        super(objectMapper);
        this.eventRepository = eventRepository;
    }

    @Override
    public String ruleType() {
        return "IP_VELOCITY_LAST_10_MIN";
    }

    @Override
    public boolean evaluate(RiskRule rule, RiskContext context) {
        if (context.ip() == null) return false;
        int threshold = parseIntField(rule.getConfigJson(), "threshold", 3);
        long count = eventRepository.countPaymentAttemptsByIpSince(
                context.ip(), LocalDateTime.now().minusMinutes(10));
        return count >= threshold;
    }
}
