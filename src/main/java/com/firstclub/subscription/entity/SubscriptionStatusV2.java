package com.firstclub.subscription.entity;

/**
 * Lifecycle states for a subscription contract (v2).
 *
 * <pre>
 * INCOMPLETE  ──► TRIALING, ACTIVE, CANCELLED
 * TRIALING    ──► ACTIVE, CANCELLED, PAST_DUE
 * ACTIVE      ──► PAST_DUE, PAUSED, CANCELLED, EXPIRED
 * PAST_DUE    ──► ACTIVE, SUSPENDED, CANCELLED
 * PAUSED      ──► ACTIVE, CANCELLED
 * SUSPENDED   ──► ACTIVE, CANCELLED
 * CANCELLED   ──► (terminal)
 * EXPIRED     ──► (terminal)
 * </pre>
 */
public enum SubscriptionStatusV2 {

    /** Payment method not yet confirmed; subscription not yet active. */
    INCOMPLETE,

    /** In a free-trial period; no payment collected yet. */
    TRIALING,

    /** Fully active; billing cycles running normally. */
    ACTIVE,

    /** Payment failed; grace period before suspension. */
    PAST_DUE,

    /** Temporarily paused; billing and access suspended until resume. */
    PAUSED,

    /** Suspended after failed payment recovery; requires manual action. */
    SUSPENDED,

    /** Cancelled by merchant or customer; terminal state. */
    CANCELLED,

    /** End-of-term expiry (no renewal configured); terminal state. */
    EXPIRED;

    /** Returns {@code true} if this is a terminal state (no further transitions). */
    public boolean isTerminal() {
        return this == CANCELLED || this == EXPIRED;
    }

    /** Returns {@code true} if the subscription is in any "live" billing state. */
    public boolean isLive() {
        return this == TRIALING || this == ACTIVE || this == PAST_DUE;
    }
}
