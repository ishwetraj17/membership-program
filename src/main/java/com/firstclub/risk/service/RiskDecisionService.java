package com.firstclub.risk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.risk.dto.RiskDecisionResponseDTO;
import com.firstclub.risk.entity.RiskAction;
import com.firstclub.risk.entity.RiskDecision;
import com.firstclub.risk.entity.RiskRule;
import com.firstclub.risk.repository.RiskDecisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full risk decision pipeline for a payment intent confirmation.
 *
 * <h3>Decision Model — Strongest Action Wins</h3>
 * The final decision is the most severe action across all matched rules:
 * {@code BLOCK > REVIEW > CHALLENGE > ALLOW}.
 * If no rules match the decision is {@code ALLOW}.
 *
 * <h3>Score</h3>
 * {@code score = Σ config_json["score"] for each matched rule}
 * Score is informational; it does not override the action-based decision.
 *
 * <h3>REVIEW side-effect</h3>
 * A {@code REVIEW} decision automatically creates a {@link ManualReviewService#createCase}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskDecisionService {

    private final RiskRuleService         ruleService;
    private final RiskDecisionRepository  decisionRepository;
    private final ManualReviewService     reviewService;
    private final ObjectMapper            objectMapper;

    // ── Core evaluation ───────────────────────────────────────────────────────

    @Transactional
    public RiskDecisionResponseDTO evaluateForPaymentIntent(RiskContext context) {
        RiskEvaluationResult evalResult = ruleService.evaluateRules(context);

        RiskAction decision         = deriveDecision(evalResult.matchedRules());
        String     matchedRulesJson = buildMatchedRulesJson(evalResult.matchedRules());

        RiskDecision riskDecision = RiskDecision.builder()
                .merchantId(context.merchantId())
                .paymentIntentId(context.paymentIntentId())
                .customerId(context.customerId())
                .score(evalResult.totalScore())
                .decision(decision)
                .matchedRulesJson(matchedRulesJson)
                .build();

        RiskDecision saved = decisionRepository.save(riskDecision);

        if (decision == RiskAction.REVIEW) {
            reviewService.createCase(
                    context.merchantId(), context.paymentIntentId(), context.customerId());
            log.info("Risk REVIEW: manual review case created for intent={} merchant={}",
                    context.paymentIntentId(), context.merchantId());
        }

        log.info("Risk decision for intent={}: {} (score={}, matchedRules={})",
                context.paymentIntentId(), decision, evalResult.totalScore(),
                evalResult.matchedRules().size());

        return RiskDecisionResponseDTO.from(saved);
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<RiskDecisionResponseDTO> listDecisions(Long merchantId, Pageable pageable) {
        if (merchantId != null) {
            return decisionRepository
                    .findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
                    .map(RiskDecisionResponseDTO::from);
        }
        return decisionRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(RiskDecisionResponseDTO::from);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * BLOCK > REVIEW > CHALLENGE > ALLOW.
     * Default when no rules matched: ALLOW.
     */
    RiskAction deriveDecision(List<RiskRule> matchedRules) {
        if (matchedRules.isEmpty()) return RiskAction.ALLOW;
        if (matchedRules.stream().anyMatch(r -> r.getAction() == RiskAction.BLOCK))     return RiskAction.BLOCK;
        if (matchedRules.stream().anyMatch(r -> r.getAction() == RiskAction.REVIEW))    return RiskAction.REVIEW;
        if (matchedRules.stream().anyMatch(r -> r.getAction() == RiskAction.CHALLENGE)) return RiskAction.CHALLENGE;
        return RiskAction.ALLOW;
    }

    private String buildMatchedRulesJson(List<RiskRule> matchedRules) {
        try {
            List<Map<String, Object>> summary = matchedRules.stream()
                    .map(r -> Map.<String, Object>of(
                            "id",       r.getId() != null ? r.getId() : 0L,
                            "ruleCode", r.getRuleCode(),
                            "action",   r.getAction().name()))
                    .toList();
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            log.warn("Failed to serialise matched rules JSON", e);
            return "[]";
        }
    }
}
