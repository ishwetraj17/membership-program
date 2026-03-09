package com.firstclub.risk.service.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.risk.entity.RiskRule;
import com.firstclub.risk.repository.IpBlocklistRepository;
import com.firstclub.risk.service.RiskContext;
import org.springframework.stereotype.Component;

/**
 * Fires when the request IP is on the platform-level IP block-list.
 *
 * config_json: {@code {"score": 100}}
 */
@Component
public class BlocklistIpEvaluator extends AbstractRuleEvaluator {

    private final IpBlocklistRepository ipBlocklistRepository;

    public BlocklistIpEvaluator(ObjectMapper objectMapper,
                                 IpBlocklistRepository ipBlocklistRepository) {
        super(objectMapper);
        this.ipBlocklistRepository = ipBlocklistRepository;
    }

    @Override
    public String ruleType() {
        return "BLOCKLIST_IP";
    }

    @Override
    public boolean evaluate(RiskRule rule, RiskContext context) {
        if (context.ip() == null) return false;
        return ipBlocklistRepository.existsById(context.ip());
    }
}
