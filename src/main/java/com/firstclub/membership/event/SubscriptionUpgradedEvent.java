package com.firstclub.membership.event;

/** Published after a subscription is successfully upgraded to a higher-tier plan. */
public class SubscriptionUpgradedEvent extends SubscriptionLifecycleEvent {

    private final Long fromPlanId;
    private final Long toPlanId;

    public SubscriptionUpgradedEvent(Object source, Long subscriptionId, Long userId,
                                     Long fromPlanId, Long toPlanId) {
        super(source, subscriptionId, userId);
        this.fromPlanId = fromPlanId;
        this.toPlanId   = toPlanId;
    }

    public Long getFromPlanId() { return fromPlanId; }
    public Long getToPlanId()   { return toPlanId; }
}
