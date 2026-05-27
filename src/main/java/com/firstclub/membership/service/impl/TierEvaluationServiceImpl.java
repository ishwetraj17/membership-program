package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.TierEligibilityResult;
import com.firstclub.membership.entity.TierEligibilityCriteria;
import com.firstclub.membership.repository.TierEligibilityCriteriaRepository;
import com.firstclub.membership.service.TierEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TierEvaluationServiceImpl implements TierEvaluationService {

    private final TierEligibilityCriteriaRepository criteriaRepository;

    @Override
    @Transactional(readOnly = true)
    public TierEligibilityResult evaluateEligibleTier(Long userId) {
        UserOrderSummary orders = fetchOrderSummary(userId);
        List<TierEligibilityCriteria> allCriteria = criteriaRepository.findAllOrderByTierLevelDesc();

        String eligibleTier = "SILVER"; // default — no criteria means always eligible
        for (TierEligibilityCriteria criteria : allCriteria) {
            if (meetsEligibility(orders, criteria)) {
                eligibleTier = criteria.getTier().getName();
                break;
            }
        }

        log.debug("User {} evaluated to tier {} (orders={}, spend={})",
                userId, eligibleTier, orders.orderCount, orders.monthlySpend);

        return TierEligibilityResult.builder()
                .userId(userId)
                .eligibleTierName(eligibleTier)
                .orderCount(orders.orderCount)
                .monthlySpend(orders.monthlySpend)
                .evaluationNote("Based on last 30 days of order activity")
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEligibleForTier(Long userId, String tierName) {
        Optional<TierEligibilityCriteria> criteria = criteriaRepository.findByTier_Name(tierName.toUpperCase());
        if (criteria.isEmpty()) {
            return true; // SILVER — no minimum requirements
        }
        return meetsEligibility(fetchOrderSummary(userId), criteria.get());
    }

    private boolean meetsEligibility(UserOrderSummary summary, TierEligibilityCriteria criteria) {
        return summary.orderCount >= criteria.getMinOrders()
                && summary.monthlySpend.compareTo(criteria.getMinMonthlySpend()) >= 0;
    }

    /**
     * DEMO STUB — returns deterministic mock order data based on userId.
     *
     * In production this would call an OrderService (or read from an orders
     * database) to get the real rolling-window aggregates. The interface is
     * intentionally isolated here so swapping the implementation requires
     * changing only this method.
     */
    private UserOrderSummary fetchOrderSummary(Long userId) {
        int orderCount = (int) (userId % 20) * 2;
        BigDecimal monthlySpend = BigDecimal.valueOf(userId * 500L).min(BigDecimal.valueOf(10_000));
        return new UserOrderSummary(orderCount, monthlySpend);
    }

    private record UserOrderSummary(int orderCount, BigDecimal monthlySpend) {}
}
