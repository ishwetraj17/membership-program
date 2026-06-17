package com.firstclub.membership.service.impl;

import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.entity.SubscriptionEvent;
import com.firstclub.membership.event.OutboxEventService;
import com.firstclub.membership.event.SubscriptionDomainEvent;
import com.firstclub.membership.repository.SubscriptionEventRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Processes a single auto-renewal in its own transaction.
 *
 * Must be a separate Spring bean so that @Transactional(REQUIRES_NEW) is
 * honoured via the proxy — self-invocation on SubscriptionServiceImpl would
 * bypass the proxy and share the caller's transaction.
 *
 * Exceptions propagate so the REQUIRES_NEW transaction rolls back this renewal atomically
 * (no partial commit if the charge or any write fails); the caller catches per-subscription so
 * the rest of the batch is unaffected.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionRenewalProcessor {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionEventRepository eventRepository;
    private final OutboxEventService outboxEventService;

    /**
     * Applies a renewal that has already been charged (the payment reference is passed in), in its
     * own REQUIRES_NEW transaction. Charging happens outside this transaction so a successful
     * charge is never committed-without / rolled-back-after by the renewal write.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyRenewal(Subscription subscription, String paymentReference) {
        LocalDateTime newStart = subscription.getEndDate();
        LocalDateTime newEnd = newStart.plusMonths(subscription.getPlan().getDurationInMonths());
        subscription.setStartDate(newStart);
        subscription.setEndDate(newEnd);
        subscription.setNextBillingDate(newEnd);
        subscription.setPaidAmount(subscription.getPlan().getPrice());
        subscriptionRepository.save(subscription);
        eventRepository.save(SubscriptionEvent.builder()
                .subscriptionId(subscription.getId())
                .userId(subscription.getUser().getId())
                .eventType(SubscriptionEvent.EventType.RENEWED)
                .amount(subscription.getPaidAmount())
                .planId(subscription.getPlan().getId())
                .tierName(subscription.getPlan().getTier().getName())
                .paymentReference(paymentReference)
                .occurredAt(newStart)
                .build());
        outboxEventService.publish(SubscriptionEvent.AGGREGATE, subscription.getId(), "RENEWED",
                new SubscriptionDomainEvent("RENEWED", subscription.getId(), subscription.getUser().getId(),
                        subscription.getPlan().getId(), subscription.getPlan().getTier().getName(),
                        subscription.getPaidAmount(), newStart));
    }
}
