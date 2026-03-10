package com.firstclub.risk.scoring;

import com.firstclub.risk.entity.RiskDecision;
import com.firstclub.risk.repository.RiskDecisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces a human-readable explanation of why a payment intent received the
 * risk decision it did.
 *
 * <p>Parses the {@code matchedRulesJson} field from the most recent
 * {@link RiskDecision} to extract triggered rule IDs and constructs a
 * plain-English narrative.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskDecisionExplainer {

    /** Matches numeric "id" fields inside the matchedRulesJson array. */
    private static final Pattern RULE_ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private final RiskDecisionRepository riskDecisionRepository;
    private final RiskScoreDecayService decayService;

    /**
     * Explain the most recent risk decision for the given payment intent.
     *
     * @param paymentIntentId the payment intent to explain
     * @return explanation record
     * @throws ResponseStatusException 404 if no decision exists
     */
    @Transactional(readOnly = true)
    public RiskDecisionExplanation explain(Long paymentIntentId) {
        RiskDecision decision = riskDecisionRepository
                .findTopByPaymentIntentIdOrderByCreatedAtDesc(paymentIntentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No risk decision found for paymentIntentId: " + paymentIntentId));

        List<Long> triggeredRuleIds = extractRuleIds(decision.getMatchedRulesJson());

        int decayed = decayService.decayedScore(decision.getScore(), decision.getCreatedAt());

        String narrative = buildNarrative(decision, triggeredRuleIds, decayed);

        log.debug("Explained risk decision for paymentIntent={} action={} score={} decayed={}",
                paymentIntentId, decision.getDecision(), decision.getScore(), decayed);

        return new RiskDecisionExplanation(
                paymentIntentId,
                decision.getDecision(),
                decision.getScore(),
                decayed,
                triggeredRuleIds,
                decision.getMatchedRulesJson(),
                narrative
        );
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private List<Long> extractRuleIds(String matchedRulesJson) {
        List<Long> ids = new ArrayList<>();
        if (matchedRulesJson == null || matchedRulesJson.isBlank()) {
            return ids;
        }
        Matcher matcher = RULE_ID_PATTERN.matcher(matchedRulesJson);
        while (matcher.find()) {
            try {
                ids.add(Long.parseLong(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // malformed JSON fragment — skip
            }
        }
        return ids;
    }

    private String buildNarrative(RiskDecision decision, List<Long> ruleIds, int decayedScore) {
        String action = decision.getDecision().name();
        int score = decision.getScore();
        long ageMinutes = java.time.Duration.between(decision.getCreatedAt(), LocalDateTime.now()).toMinutes();

        StringBuilder sb = new StringBuilder();
        sb.append("Payment intent ").append(decision.getPaymentIntentId())
                .append(" received decision ").append(action)
                .append(" with a risk score of ").append(score).append(". ");

        if (!ruleIds.isEmpty()) {
            sb.append(ruleIds.size()).append(" rule(s) fired (IDs: ").append(ruleIds).append("). ");
        } else {
            sb.append("No specific rules were matched. ");
        }

        sb.append("The decision was recorded ").append(ageMinutes).append(" minute(s) ago. ");
        sb.append("After time-decay (72-hour half-life) the score is now ").append(decayedScore).append(".");

        return sb.toString();
    }
}
