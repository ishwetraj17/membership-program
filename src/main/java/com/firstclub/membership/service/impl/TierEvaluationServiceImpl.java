package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.TierEligibilityResult;
import com.firstclub.membership.entity.TierEligibilityCriteria;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.MembershipTierRepository;
import com.firstclub.membership.repository.TierEligibilityCriteriaRepository;
import com.firstclub.membership.service.OrderService;
import com.firstclub.membership.service.OrderService.OrderSummary;
import com.firstclub.membership.service.TierEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Evaluates the highest tier a user qualifies for, from the configurable criteria table
 * (order count, monthly spend, cohort) and order metrics supplied by {@link OrderService}.
 *
 * The engine, criteria table and APIs are production-ready; only the {@link OrderService}
 * adapter is demo data today (see {@link InMemoryOrderService}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TierEvaluationServiceImpl implements TierEvaluationService {

    private final TierEligibilityCriteriaRepository criteriaRepository;
    private final MembershipTierRepository tierRepository;
    private final OrderService orderService;

    @Override
    @Transactional(readOnly = true)
    public TierEligibilityResult evaluateEligibleTier(Long userId) {
        List<TierEligibilityCriteria> allCriteria = criteriaRepository.findAllOrderByTierLevelDesc();

        String eligibleTier = "SILVER"; // default — no criteria means always eligible
        String note = "Default tier — no minimum requirements";
        OrderSummary observed = orderService.getOrderSummary(userId, DEFAULT_WINDOW_DAYS);

        for (TierEligibilityCriteria criteria : allCriteria) {
            OrderSummary orders = orderService.getOrderSummary(userId, criteria.getEvaluationPeriodDays());
            if (meetsEligibility(userId, orders, criteria)) {
                eligibleTier = criteria.getTier().getName();
                note = buildEvaluationNote(orders, criteria);
                observed = orders;
                break;
            }
        }

        log.debug("User {} evaluated to tier {} (orders={}, spend={})",
                userId, eligibleTier, observed.orderCount(), observed.totalSpend());

        return TierEligibilityResult.builder()
                .userId(userId)
                .eligibleTierName(eligibleTier)
                .orderCount(observed.orderCount())
                .monthlySpend(observed.totalSpend())
                .evaluationNote(note)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEligibleForTier(Long userId, String tierName) {
        String upper = tierName.toUpperCase();
        // Distinguish "tier exists, no criteria" (SILVER) from "tier doesn't exist".
        if (tierRepository.findByName(upper).isEmpty()) {
            throw MembershipException.tierNotFound(tierName);
        }
        Optional<TierEligibilityCriteria> criteria = criteriaRepository.findByTier_Name(upper);
        if (criteria.isEmpty()) {
            return true; // Tier exists but has no criteria (SILVER) — open to all
        }
        OrderSummary orders = orderService.getOrderSummary(userId, criteria.get().getEvaluationPeriodDays());
        return meetsEligibility(userId, orders, criteria.get());
    }

    private boolean meetsEligibility(Long userId, OrderSummary summary, TierEligibilityCriteria criteria) {
        if (summary.orderCount() < criteria.getMinOrders()) return false;
        if (summary.totalSpend().compareTo(criteria.getMinMonthlySpend()) < 0) return false;
        // Cohort gate: if the tier requires a specific cohort, the user must belong to it.
        if (criteria.getCohortCode() != null && !orderService.isUserInCohort(userId, criteria.getCohortCode())) {
            return false;
        }
        return true;
    }

    private String buildEvaluationNote(OrderSummary orders, TierEligibilityCriteria criteria) {
        String base = String.format("Last %d days: %d orders, ₹%.0f spend",
                criteria.getEvaluationPeriodDays(), orders.orderCount(), orders.totalSpend());
        return criteria.getCohortCode() != null
                ? base + " + " + criteria.getCohortCode() + " cohort membership"
                : base;
    }

    private static final int DEFAULT_WINDOW_DAYS = 30;
}
