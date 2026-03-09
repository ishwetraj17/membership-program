package com.firstclub.customer.entity;

/**
 * Lifecycle status of a {@link Customer} on the platform.
 *
 * <ul>
 *   <li>{@code ACTIVE}   – customer is in good standing; eligible for new subscriptions.</li>
 *   <li>{@code INACTIVE} – customer exists but is not currently billable (e.g. churned,
 *                          paused voluntarily).  Remains queryable.</li>
 *   <li>{@code BLOCKED}  – customer has been blocked (e.g. fraud risk, payment abuse).
 *                          Cannot be used for new subscriptions or payments.</li>
 * </ul>
 *
 * Status transitions are validated by
 * {@link com.firstclub.platform.statemachine.StateMachineValidator} with key {@code "CUSTOMER"}.
 */
public enum CustomerStatus {
    ACTIVE,
    INACTIVE,
    BLOCKED
}
