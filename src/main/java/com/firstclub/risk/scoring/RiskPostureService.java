package com.firstclub.risk.scoring;

import com.firstclub.risk.entity.RiskAction;
import com.firstclub.risk.entity.RiskDecision;
import com.firstclub.risk.repository.RiskDecisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Summarises a merchant's risk posture based on their most recent risk decisions.
 *
 * <p>Used by the risk-review controller to give operators a quick read on how
 * risky a merchant's traffic has been recently.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskPostureService {

    /** How many recent decisions to analyse per merchant. */
    static final int LOOK_BACK_LIMIT = 100;

    private final RiskDecisionRepository riskDecisionRepository;

    /**
     * Build a {@link RiskPostureSummary} for {@code merchantId} based on their
     * last {@value #LOOK_BACK_LIMIT} risk decisions.
     *
     * @param merchantId the merchant to assess
     * @return posture summary (never null)
     * @throws ResponseStatusException 404 when no decisions exist for this merchant
     */
    @Transactional(readOnly = true)
    public RiskPostureSummary getPosture(Long merchantId) {
        List<RiskDecision> recent = riskDecisionRepository
                .findByMerchantIdOrderByCreatedAtDesc(merchantId, PageRequest.of(0, LOOK_BACK_LIMIT))
                .getContent();

        if (recent.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No risk decisions found for merchant " + merchantId);
        }

        Map<RiskAction, Long> counts = recent.stream()
                .collect(Collectors.groupingBy(RiskDecision::getDecision, Collectors.counting()));

        int blockCount     = counts.getOrDefault(RiskAction.BLOCK,     0L).intValue();
        int reviewCount    = counts.getOrDefault(RiskAction.REVIEW,    0L).intValue();
        int challengeCount = counts.getOrDefault(RiskAction.CHALLENGE, 0L).intValue();
        int allowCount     = counts.getOrDefault(RiskAction.ALLOW,     0L).intValue();

        int avgScore = (int) Math.round(
                recent.stream().mapToInt(RiskDecision::getScore).average().orElse(0));

        RiskAction dominant = counts.entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(RiskAction.ALLOW);

        RiskPostureSummary summary = new RiskPostureSummary(
                merchantId,
                recent.size(),
                blockCount, reviewCount, challengeCount, allowCount,
                avgScore,
                dominant
        );
        log.debug("Risk posture for merchant {}: dominant={} avg={}", merchantId, dominant, avgScore);
        return summary;
    }
}
