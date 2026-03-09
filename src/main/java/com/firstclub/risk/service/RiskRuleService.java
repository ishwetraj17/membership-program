package com.firstclub.risk.service;

import com.firstclub.risk.dto.RiskRuleCreateRequestDTO;
import com.firstclub.risk.dto.RiskRuleResponseDTO;
import com.firstclub.risk.entity.RiskRule;
import com.firstclub.risk.repository.RiskRuleRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages risk rule CRUD and orchestrates rule evaluation.
 *
 * <h3>Evaluation Model</h3>
 * Rules are loaded in ascending priority order. Merchant-specific rules are evaluated
 * alongside platform-wide rules (merchantId = null). An unknown rule type logs a warning
 * and is skipped rather than failing fast.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskRuleService {

    private final RiskRuleRepository ruleRepository;
    private final List<RuleEvaluator> evaluators;

    private Map<String, RuleEvaluator> evaluatorMap;

    @PostConstruct
    public void init() {
        evaluatorMap = evaluators.stream()
                .collect(Collectors.toMap(RuleEvaluator::ruleType, e -> e));
        log.info("Risk rule engine ready — {} evaluator(s): {}", evaluatorMap.size(), evaluatorMap.keySet());
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Transactional
    public RiskRuleResponseDTO createRule(RiskRuleCreateRequestDTO request) {
        RiskRule rule = RiskRule.builder()
                .merchantId(request.getMerchantId())
                .ruleCode(request.getRuleCode())
                .ruleType(request.getRuleType())
                .configJson(request.getConfigJson())
                .action(request.getAction())
                .active(request.isActive())
                .priority(request.getPriority())
                .build();
        RiskRuleResponseDTO saved = RiskRuleResponseDTO.from(ruleRepository.save(rule));
        log.info("Created risk rule {} (type={}, action={}, merchantId={})",
                saved.ruleCode(), saved.ruleType(), saved.action(), saved.merchantId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<RiskRuleResponseDTO> listRules(Pageable pageable) {
        return ruleRepository.findAll(pageable).map(RiskRuleResponseDTO::from);
    }

    @Transactional
    public RiskRuleResponseDTO updateRule(Long ruleId, RiskRuleCreateRequestDTO request) {
        RiskRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new EntityNotFoundException("RiskRule not found: " + ruleId));
        rule.setRuleCode(request.getRuleCode());
        rule.setRuleType(request.getRuleType());
        rule.setConfigJson(request.getConfigJson());
        rule.setAction(request.getAction());
        rule.setActive(request.isActive());
        rule.setPriority(request.getPriority());
        log.info("Updated risk rule {} (type={}, action={})", ruleId, request.getRuleType(), request.getAction());
        return RiskRuleResponseDTO.from(ruleRepository.save(rule));
    }

    // ── Evaluation ────────────────────────────────────────────────────────────

    /**
     * Evaluates all active rules that apply to the merchant (merchant-specific + platform-wide),
     * returning the matched rules and their aggregate score.
     */
    @Transactional(readOnly = true)
    public RiskEvaluationResult evaluateRules(RiskContext context) {
        List<RiskRule> rules = ruleRepository.findActiveRulesForMerchant(context.merchantId());

        List<RiskRule> matched = new ArrayList<>();
        int totalScore = 0;

        for (RiskRule rule : rules) {
            RuleEvaluator evaluator = evaluatorMap.get(rule.getRuleType());
            if (evaluator == null) {
                log.warn("No evaluator registered for rule type '{}' (ruleCode={}); skipping",
                        rule.getRuleType(), rule.getRuleCode());
                continue;
            }
            if (evaluator.evaluate(rule, context)) {
                int ruleScore = evaluator.score(rule);
                matched.add(rule);
                totalScore += ruleScore;
                log.debug("Rule matched: code={} type={} action={} score+{}",
                        rule.getRuleCode(), rule.getRuleType(), rule.getAction(), ruleScore);
            }
        }

        log.debug("Evaluated {} rule(s) for merchant={} intent={}: matched={} totalScore={}",
                rules.size(), context.merchantId(), context.paymentIntentId(),
                matched.size(), totalScore);

        return new RiskEvaluationResult(matched, totalScore);
    }
}
