package com.firstclub.outbox.handler;

import com.firstclub.outbox.entity.OutboxEvent;

/**
 * Strategy interface for handling a specific type of domain event from the
 * transactional outbox.
 *
 * <p>Implementations <strong>must be idempotent</strong>: the same event may
 * be delivered more than once (at-least-once delivery).  Each handler should
 * verify the current DB state before applying any side effect.
 *
 * <p>Throwing any exception signals a processing failure; {@link
 * com.firstclub.outbox.service.OutboxService} will schedule a retry.
 */
public interface OutboxEventHandler {

    /** The event type string this handler processes (see {@link com.firstclub.outbox.config.DomainEventTypes}). */
    String getEventType();

    /**
     * Processes the event.
     *
     * @param event the full outbox row, including the JSON payload
     * @throws Exception on any unrecoverable processing failure
     */
    void handle(OutboxEvent event) throws Exception;
}
