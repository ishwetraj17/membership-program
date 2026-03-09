package com.firstclub.catalog.entity;

/**
 * Determines how a {@link Price} is charged.
 *
 * <ul>
 *   <li>{@link #RECURRING} – charged on a repeating schedule defined by
 *       {@code billingIntervalUnit} × {@code billingIntervalCount}.</li>
 *   <li>{@link #ONE_TIME}  – charged once at the time of purchase/activation.</li>
 * </ul>
 */
public enum BillingType {
    RECURRING,
    ONE_TIME
}
