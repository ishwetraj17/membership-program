package com.firstclub.membership.event;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * In-process consumer of subscription domain events. Runs only after the producing
 * transaction commits (AFTER_COMMIT), so it never reacts to rolled-back changes.
 *
 * In production this is where side effects like welcome emails, push notifications or
 * analytics fan-out would live; here it logs and increments a per-type metric.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionEventListener {

    private final MeterRegistry meterRegistry;

    @TransactionalEventListener
    public void onSubscriptionEvent(SubscriptionDomainEvent event) {
        meterRegistry.counter("membership.subscription.events", "type", event.eventType()).increment();
        log.info("Domain event (after commit): {} — subscription={} user={} tier={}",
                event.eventType(), event.subscriptionId(), event.userId(), event.tierName());
    }
}
