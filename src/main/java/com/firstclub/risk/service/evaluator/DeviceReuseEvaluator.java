package com.firstclub.risk.service.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.risk.entity.RiskRule;
import com.firstclub.risk.repository.RiskEventRepository;
import com.firstclub.risk.service.RiskContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Fires when the device fingerprint has been used by too many distinct users
 * in a configurable lookback window — a signal for shared/reused device fraud.
 *
 * config_json: {@code {"threshold": 3, "lookbackHours": 24, "score": 40}}
 */
@Component
public class DeviceReuseEvaluator extends AbstractRuleEvaluator {

    private final RiskEventRepository eventRepository;

    public DeviceReuseEvaluator(ObjectMapper objectMapper,
                                 RiskEventRepository eventRepository) {
        super(objectMapper);
        this.eventRepository = eventRepository;
    }

    @Override
    public String ruleType() {
        return "DEVICE_REUSE";
    }

    @Override
    public boolean evaluate(RiskRule rule, RiskContext context) {
        if (context.deviceId() == null) return false;
        int threshold    = parseIntField(rule.getConfigJson(), "threshold",    3);
        int lookbackHours = parseIntField(rule.getConfigJson(), "lookbackHours", 24);
        long distinctUsers = eventRepository.countDistinctUsersByDeviceIdSince(
                context.deviceId(), LocalDateTime.now().minusHours(lookbackHours));
        return distinctUsers >= threshold;
    }
}
