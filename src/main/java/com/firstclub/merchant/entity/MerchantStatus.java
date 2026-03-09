package com.firstclub.merchant.entity;

/**
 * Lifecycle status for merchant accounts.
 *
 * <p>State machine (enforced by {@link com.firstclub.platform.statemachine.StateMachineValidator}):
 * <pre>
 *   PENDING ──► ACTIVE ──► SUSPENDED ──► CLOSED
 *      └──────────────────────────────────────►┘
 * </pre>
 * CLOSED is terminal — a closed merchant cannot be re-opened.
 */
public enum MerchantStatus {
    /** Merchant registered but not yet verified / activated by platform admin. */
    PENDING,
    /** Fully operational — may process payments and create customer flows. */
    ACTIVE,
    /** Temporarily disabled (fraud, compliance hold, etc.). */
    SUSPENDED,
    /** Permanently closed — no further operations allowed. Terminal state. */
    CLOSED
}
