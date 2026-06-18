package com.firstclub.membership.event;

import com.firstclub.membership.service.EntitlementsService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * In-process consumer of subscription domain events. Runs only after the producing
 * transaction commits (AFTER_COMMIT), so it never reacts to rolled-back changes.
 *
 * Every lifecycle change (created/renewed/upgraded/downgraded/cancelled/expired/refunded) flows
 * through here, so this is also where the user's cached entitlements are invalidated — reusing
 * the existing event model rather than adding a parallel notification path.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionEventListener {

    private final MeterRegistry meterRegistry;
    private final EntitlementsService entitlementsService;

    @TransactionalEventListener
    public void onSubscriptionEvent(SubscriptionDomainEvent event) {
        meterRegistry.counter("membership.subscription.events", "type", event.eventType()).increment();
        // Entitlements changed for this user — drop the cached copy so the next read is fresh.
        entitlementsService.invalidate(event.userId());
        log.info("Domain event (after commit): {} — subscription={} user={} tier={}",
                event.eventType(), event.subscriptionId(), event.userId(), event.tierName());
    }
}
