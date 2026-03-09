package com.firstclub.membership.event;

/** Published after a subscription is successfully downgraded to a lower-tier plan. */
public class SubscriptionDowngradedEvent extends SubscriptionLifecycleEvent {

    private final Long fromPlanId;
    private final Long toPlanId;

    public SubscriptionDowngradedEvent(Object source, Long subscriptionId, Long userId,
                                       Long fromPlanId, Long toPlanId) {
        super(source, subscriptionId, userId);
        this.fromPlanId = fromPlanId;
        this.toPlanId   = toPlanId;
    }

    public Long getFromPlanId() { return fromPlanId; }
    public Long getToPlanId()   { return toPlanId; }
}
