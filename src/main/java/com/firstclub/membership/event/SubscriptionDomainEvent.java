package com.firstclub.membership.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A subscription lifecycle domain event. Published in-process (Spring
 * {@code ApplicationEventPublisher}) and persisted to the transactional outbox for
 * reliable downstream delivery.
 */
public record SubscriptionDomainEvent(
        String eventType,
        Long subscriptionId,
        Long userId,
        Long planId,
        String tierName,
        BigDecimal amount,
        LocalDateTime occurredAt) {
}
