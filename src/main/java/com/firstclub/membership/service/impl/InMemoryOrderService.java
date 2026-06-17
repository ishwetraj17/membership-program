package com.firstclub.membership.service.impl;

import com.firstclub.membership.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demo adapter for {@link OrderService}.
 *
 * Holds order metrics and cohort assignments in memory. Specific users can be seeded
 * (and updated at runtime via {@link #recordOrders}); unseeded users fall back to a
 * deterministic derivation so any user id yields stable, explainable eligibility.
 *
 * In production this bean is replaced by an adapter that calls the real Order Service /
 * Customer Segmentation service — nothing else in the eligibility path changes.
 */
@Service
@Slf4j
public class InMemoryOrderService implements OrderService {

    private final Map<Long, OrderSummary> summaries = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> cohorts = new ConcurrentHashMap<>();

    public InMemoryOrderService() {
        // Demo seed aligned with the three dev users so the eligibility demo shows a spread:
        //   user 1 → Silver, user 2 → Platinum (cohort + high spend), user 3 → Gold
        summaries.put(1L, new OrderSummary(3, new BigDecimal("1500")));
        summaries.put(2L, new OrderSummary(20, new BigDecimal("8000")));
        summaries.put(3L, new OrderSummary(8, new BigDecimal("3000")));
        cohorts.put(2L, Set.of("PREMIUM_COHORT"));
    }

    @Override
    public OrderSummary getOrderSummary(Long userId, int windowDays) {
        return summaries.computeIfAbsent(userId, this::derive);
    }

    @Override
    public boolean isUserInCohort(Long userId, String cohortCode) {
        Set<String> assigned = cohorts.get(userId);
        if (assigned != null && assigned.contains(cohortCode)) {
            return true;
        }
        // Deterministic fallback for unseeded users: even ids belong to PREMIUM_COHORT.
        return "PREMIUM_COHORT".equals(cohortCode) && userId % 2 == 0;
    }

    /** Ops/test hook — record or overwrite a user's order metrics. */
    public void recordOrders(Long userId, int orderCount, BigDecimal totalSpend) {
        summaries.put(userId, new OrderSummary(orderCount, totalSpend));
    }

    private OrderSummary derive(Long userId) {
        int orderCount = (int) (userId % 20) * 2;
        BigDecimal spend = BigDecimal.valueOf(userId * 500L).min(BigDecimal.valueOf(10_000));
        return new OrderSummary(orderCount, spend);
    }
}
