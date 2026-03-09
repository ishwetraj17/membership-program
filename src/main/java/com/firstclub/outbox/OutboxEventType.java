package com.firstclub.outbox;

/**
 * Compile-time enumeration of all outbox event types that <em>must</em> have a
 * registered {@link com.firstclub.outbox.handler.OutboxEventHandler} bean.
 *
 * <p>The {@link com.firstclub.outbox.handler.OutboxEventHandlerRegistry} validates at
 * application startup that every constant declared here has a corresponding
 * {@code @Component} handler.  If any type is missing a handler, the application
 * fails to start — surfacing the gap at deploy time rather than as silent runtime
 * data loss.
 *
 * <h3>Adding a new event type</h3>
 * <ol>
 *   <li>Add a constant here.</li>
 *   <li>Create a {@code @Component} class implementing
 *       {@link com.firstclub.outbox.handler.OutboxEventHandler} whose
 *       {@code getEventType()} returns {@link #name()}.</li>
 *   <li>If no handler is warranted (monitoring-only or fire-and-forget types),
 *       do <em>not</em> add the constant here — put the string in
 *       {@link com.firstclub.outbox.config.DomainEventTypes} instead.</li>
 * </ol>
 */
public enum OutboxEventType {

    INVOICE_CREATED,
    PAYMENT_SUCCEEDED,
    SUBSCRIPTION_ACTIVATED,
    REFUND_ISSUED;

    /**
     * Returns the string stored in {@code outbox_events.event_type}, which is
     * identical to {@link #name()}.  Provided for symmetry with the legacy
     * {@link com.firstclub.outbox.config.DomainEventTypes} string constants.
     */
    public String eventTypeName() {
        return name();
    }
}
