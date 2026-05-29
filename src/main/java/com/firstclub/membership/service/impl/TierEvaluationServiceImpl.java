package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.TierEligibilityResult;
import com.firstclub.membership.entity.TierEligibilityCriteria;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.MembershipTierRepository;
import com.firstclub.membership.repository.TierEligibilityCriteriaRepository;
import com.firstclub.membership.service.TierEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * DEMO IMPLEMENTATION
 *
 * Tier eligibility is currently calculated using deterministic mock data
 * because the assignment does not provide a real Order Service,
 * transaction history, or customer analytics source.
 *
 * In production, this service would integrate with:
 * * Order Service (order count)
 * * Payments/Commerce Service (monthly spend)
 * * Customer Segmentation Service (cohort membership)
 *
 * Eligibility enforcement is intentionally not applied to subscription
 * creation in this demo implementation to avoid making business decisions
 * based on synthetic data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TierEvaluationServiceImpl implements TierEvaluationService {

    private final TierEligibilityCriteriaRepository criteriaRepository;
    private final MembershipTierRepository tierRepository;

    @Override
    @Transactional(readOnly = true)
    public TierEligibilityResult evaluateEligibleTier(Long userId) {
        UserOrderSummary orders = fetchOrderSummary(userId);
        List<TierEligibilityCriteria> allCriteria = criteriaRepository.findAllOrderByTierLevelDesc();

        String eligibleTier = "SILVER"; // default — no criteria means always eligible
        String note = "Default tier — no minimum requirements";

        for (TierEligibilityCriteria criteria : allCriteria) {
            if (meetsEligibility(userId, orders, criteria)) {
                eligibleTier = criteria.getTier().getName();
                note = buildEvaluationNote(orders, criteria);
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
                .evaluationNote(note)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEligibleForTier(Long userId, String tierName) {
        String upper = tierName.toUpperCase();
        // Finding 2: distinguish "tier exists, no criteria" (SILVER) from "tier doesn't exist"
        if (tierRepository.findByName(upper).isEmpty()) {
            throw MembershipException.tierNotFound(tierName);
        }
        Optional<TierEligibilityCriteria> criteria = criteriaRepository.findByTier_Name(upper);
        if (criteria.isEmpty()) {
            return true; // Tier exists but has no criteria (SILVER) — open to all
        }
        return meetsEligibility(userId, fetchOrderSummary(userId), criteria.get());
    }

    private boolean meetsEligibility(Long userId, UserOrderSummary summary, TierEligibilityCriteria criteria) {
        if (summary.orderCount < criteria.getMinOrders()) return false;
        if (summary.monthlySpend.compareTo(criteria.getMinMonthlySpend()) < 0) return false;
        // Cohort gate: if the tier requires a specific cohort, the user must belong to it
        if (criteria.getCohortCode() != null && !isUserInCohort(userId, criteria.getCohortCode())) return false;
        return true;
    }

    private String buildEvaluationNote(UserOrderSummary orders, TierEligibilityCriteria criteria) {
        String base = String.format("Last %d days: %d orders, ₹%.0f spend",
                criteria.getEvaluationPeriodDays(), orders.orderCount, orders.monthlySpend);
        return criteria.getCohortCode() != null
                ? base + " + " + criteria.getCohortCode() + " cohort membership"
                : base;
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

    /**
     * DEMO STUB — returns deterministic cohort assignment based on userId.
     *
     * In production this would query a cohort assignment table or call a
     * UserProfile service. The even/odd split provides observable, testable
     * differentiation without any external dependency.
     */
    private boolean isUserInCohort(Long userId, String cohortCode) {
        return switch (cohortCode) {
            case "PREMIUM_COHORT" -> userId % 2 == 0;
            default -> false;
        };
    }

    private record UserOrderSummary(int orderCount, BigDecimal monthlySpend) {}
}
