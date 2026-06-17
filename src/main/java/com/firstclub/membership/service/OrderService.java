package com.firstclub.membership.service;

import java.math.BigDecimal;

/**
 * Port for the order/commerce data that drives tier eligibility.
 *
 * The membership domain does not own order history; in production this is backed by a
 * real Order Service / data warehouse and a Customer Segmentation service. Isolating it
 * behind this interface means the eligibility engine, criteria table and APIs are all
 * production-ready — only the adapter changes.
 *
 * @see com.firstclub.membership.service.impl.InMemoryOrderService the demo adapter
 */
public interface OrderService {

    /**
     * Rolling-window order metrics for a user.
     *
     * @param userId     the user
     * @param windowDays the evaluation window in days (e.g. 30)
     */
    OrderSummary getOrderSummary(Long userId, int windowDays);

    /** Whether the user belongs to the given segmentation cohort. */
    boolean isUserInCohort(Long userId, String cohortCode);

    record OrderSummary(int orderCount, BigDecimal totalSpend) {}
}
