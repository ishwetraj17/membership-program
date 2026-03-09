package com.firstclub.subscription.exception;

import com.firstclub.subscription.entity.SubscriptionStatusV2;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates allowed {@link SubscriptionStatusV2} state transitions.
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
public final class SubscriptionStateMachine {

    private static final Map<SubscriptionStatusV2, Set<SubscriptionStatusV2>> ALLOWED =
            Map.of(
                SubscriptionStatusV2.INCOMPLETE,  EnumSet.of(
                        SubscriptionStatusV2.TRIALING,
                        SubscriptionStatusV2.ACTIVE,
                        SubscriptionStatusV2.CANCELLED),

                SubscriptionStatusV2.TRIALING,    EnumSet.of(
                        SubscriptionStatusV2.ACTIVE,
                        SubscriptionStatusV2.CANCELLED,
                        SubscriptionStatusV2.PAST_DUE),

                SubscriptionStatusV2.ACTIVE,      EnumSet.of(
                        SubscriptionStatusV2.PAST_DUE,
                        SubscriptionStatusV2.PAUSED,
                        SubscriptionStatusV2.CANCELLED,
                        SubscriptionStatusV2.EXPIRED),

                SubscriptionStatusV2.PAST_DUE,    EnumSet.of(
                        SubscriptionStatusV2.ACTIVE,
                        SubscriptionStatusV2.SUSPENDED,
                        SubscriptionStatusV2.CANCELLED),

                SubscriptionStatusV2.PAUSED,      EnumSet.of(
                        SubscriptionStatusV2.ACTIVE,
                        SubscriptionStatusV2.CANCELLED),

                SubscriptionStatusV2.SUSPENDED,   EnumSet.of(
                        SubscriptionStatusV2.ACTIVE,
                        SubscriptionStatusV2.CANCELLED),

                SubscriptionStatusV2.CANCELLED,   EnumSet.noneOf(SubscriptionStatusV2.class),
                SubscriptionStatusV2.EXPIRED,     EnumSet.noneOf(SubscriptionStatusV2.class)
            );

    private SubscriptionStateMachine() {}

    /**
     * Throws {@link SubscriptionException} if the transition {@code from → to}
     * is not allowed.
     */
    public static void assertTransition(SubscriptionStatusV2 from, SubscriptionStatusV2 to) {
        Set<SubscriptionStatusV2> allowed = ALLOWED.getOrDefault(from, EnumSet.noneOf(SubscriptionStatusV2.class));
        if (!allowed.contains(to)) {
            throw SubscriptionException.invalidStateTransition(from, to);
        }
    }

    /** Returns {@code true} if the transition is valid without throwing. */
    public static boolean isAllowed(SubscriptionStatusV2 from, SubscriptionStatusV2 to) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(SubscriptionStatusV2.class)).contains(to);
    }
}
