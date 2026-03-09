package com.firstclub.catalog.entity;

/**
 * Calendar unit used together with {@code billingIntervalCount} to express a
 * recurring billing cycle for a {@link Price}.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code MONTH × 1} → monthly</li>
 *   <li>{@code MONTH × 3} → quarterly</li>
 *   <li>{@code YEAR  × 1} → annual</li>
 *   <li>{@code DAY   × 7} → weekly (equivalent to {@code WEEK × 1})</li>
 * </ul>
 */
public enum BillingIntervalUnit {
    DAY,
    WEEK,
    MONTH,
    YEAR
}
