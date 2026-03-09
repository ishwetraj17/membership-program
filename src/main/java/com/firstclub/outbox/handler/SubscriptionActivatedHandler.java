package com.firstclub.outbox.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.entity.Subscription.SubscriptionStatus;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.outbox.config.DomainEventTypes;
import com.firstclub.outbox.entity.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles {@link DomainEventTypes#SUBSCRIPTION_ACTIVATED} events.
 *
 * <p><b>Idempotency</b>: verifies the subscription is ACTIVE before logging.
 * If already ACTIVE (re-delivery), the handler succeeds silently.  If the
 * subscription is in an unexpected state, it throws to trigger a retry.
 *
 * <p>This handler is the natural extension point for downstream side-effects
 * such as sending a welcome email, updating analytics, or syncing to an
 * external CRM.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionActivatedHandler implements OutboxEventHandler {

    private final SubscriptionRepository subscriptionRepository;
    private final ObjectMapper           objectMapper;

    @Override
    public String getEventType() {
        return DomainEventTypes.SUBSCRIPTION_ACTIVATED;
    }

    @Override
    @Transactional(readOnly = true)
    public void handle(OutboxEvent event) throws Exception {
        JsonNode json           = objectMapper.readTree(event.getPayload());
        Long     subscriptionId = json.get("subscriptionId").asLong();

        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException(
                        "SUBSCRIPTION_ACTIVATED handler: subscription " + subscriptionId + " not found"));

        if (sub.getStatus() != SubscriptionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "SUBSCRIPTION_ACTIVATED handler: subscription " + subscriptionId +
                            " expected ACTIVE but was " + sub.getStatus() +
                            " — transient inconsistency, will retry");
        }

        Long userId = sub.getUser() != null ? sub.getUser().getId()
                                            : json.path("userId").asLong(-1);

        log.info("[SUBSCRIPTION_ACTIVATED] Subscription {} is ACTIVE — userId={}, planId={}",
                subscriptionId, userId,
                sub.getPlan() != null ? sub.getPlan().getId() : "unknown");
    }
}
