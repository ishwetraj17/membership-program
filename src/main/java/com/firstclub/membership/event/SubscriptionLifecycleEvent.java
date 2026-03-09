package com.firstclub.membership.event;

import org.springframework.context.ApplicationEvent;

/**
 * Abstract base for all subscription lifecycle events.
 *
 * <p>Published by {@code MembershipServiceImpl} after a significant subscription
 * state transition commits to the database.  Consumed by
 * {@link SubscriptionEventListener} which writes a corresponding entry to the
 * {@code audit_logs} table using a {@code REQUIRES_NEW} transaction so that an
 * audit-write failure can never roll back the already-committed business operation.
 *
 * Implemented by Shwet Raj
 */
public abstract class SubscriptionLifecycleEvent extends ApplicationEvent {

    private final Long subscriptionId;
    private final Long userId;

    protected SubscriptionLifecycleEvent(Object source, Long subscriptionId, Long userId) {
        super(source);
        this.subscriptionId = subscriptionId;
        this.userId         = userId;
    }

    public Long getSubscriptionId() { return subscriptionId; }
    public Long getUserId()         { return userId; }
}
