package com.firstclub.events.event;

import com.firstclub.events.entity.DomainEvent;
import org.springframework.context.ApplicationEvent;

/**
 * Spring {@link ApplicationEvent} published immediately after a {@link DomainEvent}
 * is persisted to the domain_events table.
 *
 * <p>Listeners (e.g. {@code ProjectionEventListener}) consume this event
 * asynchronously to update read-model projections without blocking the
 * originating transaction.
 */
public class DomainEventRecordedEvent extends ApplicationEvent {

    private final DomainEvent domainEvent;

    public DomainEventRecordedEvent(Object source, DomainEvent domainEvent) {
        super(source);
        this.domainEvent = domainEvent;
    }

    public DomainEvent getDomainEvent() {
        return domainEvent;
    }
}
