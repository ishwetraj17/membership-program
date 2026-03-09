package com.firstclub.membership.event;

/** Published after a new subscription is successfully created. */
public class SubscriptionCreatedEvent extends SubscriptionLifecycleEvent {

    private final Long planId;

    public SubscriptionCreatedEvent(Object source, Long subscriptionId, Long userId, Long planId) {
        super(source, subscriptionId, userId);
        this.planId = planId;
    }

    public Long getPlanId() { return planId; }
}
