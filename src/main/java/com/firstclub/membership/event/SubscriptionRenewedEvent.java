package com.firstclub.membership.event;

/** Published after an expired subscription is successfully renewed. */
public class SubscriptionRenewedEvent extends SubscriptionLifecycleEvent {

    private final Long planId;

    public SubscriptionRenewedEvent(Object source, Long subscriptionId, Long userId, Long planId) {
        super(source, subscriptionId, userId);
        this.planId = planId;
    }

    public Long getPlanId() { return planId; }
}
