package com.firstclub.membership.event;

/** Published after a subscription is successfully cancelled. */
public class SubscriptionCancelledEvent extends SubscriptionLifecycleEvent {

    private final String reason;

    public SubscriptionCancelledEvent(Object source, Long subscriptionId, Long userId, String reason) {
        super(source, subscriptionId, userId);
        this.reason = reason;
    }

    public String getReason() { return reason; }
}
