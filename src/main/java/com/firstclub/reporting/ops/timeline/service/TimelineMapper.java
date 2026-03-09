package com.firstclub.reporting.ops.timeline.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.service.DomainEventTypes;
import com.firstclub.reporting.ops.timeline.entity.TimelineEntityTypes;
import com.firstclub.reporting.ops.timeline.entity.TimelineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maps a single {@link DomainEvent} to zero, one, or two {@link TimelineEvent}
 * rows.  Pure data-transformation: no DB or cache access here.
 *
 * <h3>Multi-row strategy</h3>
 * Many events touch more than one entity (e.g. PAYMENT_SUCCEEDED affects both
 * the PAYMENT_INTENT and the INVOICE it settles).  The mapper emits a row for
 * each relevant entity type so that
 * {@code GET /timeline/invoice/{id}} and
 * {@code GET /timeline/payment/{id}} both surface the event.
 *
 * <p>Key subscription lifecycle events also emit a CUSTOMER-typed row so that
 * {@code GET /timeline/customer/{id}} returns a rich history without a separate
 * join.
 *
 * <h3>Payload field conventions</h3>
 * <ul>
 *   <li>Subscription events: {@code subscriptionId}, {@code customerId}
 *   <li>Invoice events:       {@code invoiceId}, {@code subscriptionId}
 *   <li>Payment events:       {@code paymentIntentId}, {@code invoiceId}
 *   <li>Refund events:        {@code refundId}, {@code paymentIntentId}
 *   <li>Dispute events:       {@code disputeId}, {@code paymentId}, {@code customerId}
 *   <li>Risk events:          {@code paymentIntentId}, {@code decision}
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TimelineMapper {

    private static final int PAYLOAD_PREVIEW_MAX = 500;

    private final ObjectMapper objectMapper;

    /**
     * Convert a domain event into zero-or-more timeline rows.
     * Returns an empty list for event types that are not mapped to any entity.
     */
    public List<TimelineEvent> map(DomainEvent event) {
        if (event.getMerchantId() == null) {
            log.debug("TimelineMapper: skipping event id={} — no merchantId", event.getId());
            return Collections.emptyList();
        }
        try {
            return switch (event.getEventType()) {
                case DomainEventTypes.SUBSCRIPTION_CREATED   -> mapSubscriptionCreated(event);
                case DomainEventTypes.SUBSCRIPTION_ACTIVATED -> mapSubLifecycle(event, "Subscription activated");
                case DomainEventTypes.SUBSCRIPTION_PAST_DUE  -> mapSubLifecycle(event, "Subscription past due");
                case DomainEventTypes.SUBSCRIPTION_SUSPENDED -> mapSubLifecycle(event, "Subscription suspended");
                case DomainEventTypes.SUBSCRIPTION_CANCELLED -> mapSubCancelled(event);
                case DomainEventTypes.INVOICE_CREATED        -> mapInvoiceCreated(event);
                case DomainEventTypes.PAYMENT_INTENT_CREATED -> mapPaymentIntentCreated(event);
                case DomainEventTypes.PAYMENT_ATTEMPT_STARTED -> mapPaymentAttemptStarted(event);
                case DomainEventTypes.PAYMENT_ATTEMPT_FAILED  -> mapPaymentAttemptFailed(event);
                case DomainEventTypes.PAYMENT_SUCCEEDED       -> mapPaymentSucceeded(event);
                case DomainEventTypes.REFUND_ISSUED           -> mapRefund(event, "Refund issued");
                case DomainEventTypes.REFUND_COMPLETED        -> mapRefund(event, "Refund completed");
                case DomainEventTypes.DISPUTE_OPENED          -> mapDisputeOpened(event);
                case DomainEventTypes.RISK_DECISION_MADE      -> mapRiskDecision(event);
                default -> Collections.emptyList();
            };
        } catch (Exception ex) {
            log.warn("TimelineMapper: failed to map event type={} id={}: {}",
                    event.getEventType(), event.getId(), ex.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Subscription events ───────────────────────────────────────────────────

    private List<TimelineEvent> mapSubscriptionCreated(DomainEvent event) {
        Long subId = extractLong(event, "subscriptionId");
        if (subId == null) return Collections.emptyList();

        Long customerId = extractLong(event, "customerId");
        List<TimelineEvent> rows = new ArrayList<>();
        rows.add(entry(event, TimelineEntityTypes.SUBSCRIPTION, subId,
                "Subscription created", null,
                TimelineEntityTypes.CUSTOMER, customerId));
        if (customerId != null) {
            rows.add(entry(event, TimelineEntityTypes.CUSTOMER, customerId,
                    "Subscription created", null,
                    TimelineEntityTypes.SUBSCRIPTION, subId));
        }
        return rows;
    }

    private List<TimelineEvent> mapSubLifecycle(DomainEvent event, String title) {
        Long subId = extractLong(event, "subscriptionId");
        if (subId == null) return Collections.emptyList();

        Long customerId = extractLong(event, "customerId");
        List<TimelineEvent> rows = new ArrayList<>();
        rows.add(entry(event, TimelineEntityTypes.SUBSCRIPTION, subId,
                title, null,
                TimelineEntityTypes.CUSTOMER, customerId));
        if (customerId != null) {
            rows.add(entry(event, TimelineEntityTypes.CUSTOMER, customerId,
                    title, null,
                    TimelineEntityTypes.SUBSCRIPTION, subId));
        }
        return rows;
    }

    private List<TimelineEvent> mapSubCancelled(DomainEvent event) {
        Long subId = extractLong(event, "subscriptionId");
        if (subId == null) return Collections.emptyList();

        Long customerId = extractLong(event, "customerId");
        List<TimelineEvent> rows = new ArrayList<>();
        rows.add(entry(event, TimelineEntityTypes.SUBSCRIPTION, subId,
                "Subscription cancelled", null,
                TimelineEntityTypes.CUSTOMER, customerId));
        if (customerId != null) {
            rows.add(entry(event, TimelineEntityTypes.CUSTOMER, customerId,
                    "Subscription cancelled", null,
                    TimelineEntityTypes.SUBSCRIPTION, subId));
        }
        return rows;
    }

    // ── Invoice events ────────────────────────────────────────────────────────

    private List<TimelineEvent> mapInvoiceCreated(DomainEvent event) {
        Long invoiceId = extractLong(event, "invoiceId");
        if (invoiceId == null) return Collections.emptyList();

        Long subId = extractLong(event, "subscriptionId");
        List<TimelineEvent> rows = new ArrayList<>();
        rows.add(entry(event, TimelineEntityTypes.INVOICE, invoiceId,
                "Invoice created", null,
                TimelineEntityTypes.SUBSCRIPTION, subId));
        if (subId != null) {
            rows.add(entry(event, TimelineEntityTypes.SUBSCRIPTION, subId,
                    "Invoice created", null,
                    TimelineEntityTypes.INVOICE, invoiceId));
        }
        return rows;
    }

    // ── Payment events ────────────────────────────────────────────────────────

    private List<TimelineEvent> mapPaymentIntentCreated(DomainEvent event) {
        Long piId = extractLong(event, "paymentIntentId");
        if (piId == null) return Collections.emptyList();

        Long invoiceId = extractLong(event, "invoiceId");
        List<TimelineEvent> rows = new ArrayList<>();
        rows.add(entry(event, TimelineEntityTypes.PAYMENT_INTENT, piId,
                "Payment intent created", null,
                TimelineEntityTypes.INVOICE, invoiceId));
        if (invoiceId != null) {
            rows.add(entry(event, TimelineEntityTypes.INVOICE, invoiceId,
                    "Payment started", null,
                    TimelineEntityTypes.PAYMENT_INTENT, piId));
        }
        return rows;
    }

    private List<TimelineEvent> mapPaymentAttemptStarted(DomainEvent event) {
        Long piId = extractLong(event, "paymentIntentId");
        if (piId == null) return Collections.emptyList();

        String gateway = extractString(event, "gatewayName");
        return List.of(entry(event, TimelineEntityTypes.PAYMENT_INTENT, piId,
                "Payment attempt started",
                gateway != null ? "Gateway: " + gateway : null,
                null, null));
    }

    private List<TimelineEvent> mapPaymentAttemptFailed(DomainEvent event) {
        Long piId = extractLong(event, "paymentIntentId");
        if (piId == null) return Collections.emptyList();

        String gateway  = extractString(event, "gatewayName");
        String category = extractString(event, "failureCategory");
        String summary  = buildFailureSummary(gateway, category);
        return List.of(entry(event, TimelineEntityTypes.PAYMENT_INTENT, piId,
                "Payment attempt failed", summary, null, null));
    }

    private List<TimelineEvent> mapPaymentSucceeded(DomainEvent event) {
        Long piId = extractLong(event, "paymentIntentId");
        if (piId == null) return Collections.emptyList();

        Long invoiceId = extractLong(event, "invoiceId");
        List<TimelineEvent> rows = new ArrayList<>();
        rows.add(entry(event, TimelineEntityTypes.PAYMENT_INTENT, piId,
                "Payment succeeded", null,
                TimelineEntityTypes.INVOICE, invoiceId));
        if (invoiceId != null) {
            rows.add(entry(event, TimelineEntityTypes.INVOICE, invoiceId,
                    "Invoice paid", null,
                    TimelineEntityTypes.PAYMENT_INTENT, piId));
        }
        return rows;
    }

    // ── Refund events ─────────────────────────────────────────────────────────

    private List<TimelineEvent> mapRefund(DomainEvent event, String title) {
        // Try both refundId and refundV2Id (legacy vs V2 refund entities)
        Long refundId = extractLong(event, "refundId");
        if (refundId == null) refundId = extractLong(event, "refundV2Id");
        if (refundId == null) return Collections.emptyList();

        Long piId = extractLong(event, "paymentIntentId");
        return List.of(entry(event, TimelineEntityTypes.REFUND, refundId,
                title, null,
                TimelineEntityTypes.PAYMENT_INTENT, piId));
    }

    // ── Dispute events ────────────────────────────────────────────────────────

    private List<TimelineEvent> mapDisputeOpened(DomainEvent event) {
        Long disputeId = extractLong(event, "disputeId");
        if (disputeId == null) return Collections.emptyList();

        Long paymentId = extractLong(event, "paymentId");
        Long customerId = extractLong(event, "customerId");

        List<TimelineEvent> rows = new ArrayList<>();
        rows.add(entry(event, TimelineEntityTypes.DISPUTE, disputeId,
                "Dispute opened", null,
                TimelineEntityTypes.PAYMENT_INTENT, paymentId));
        if (customerId != null) {
            rows.add(entry(event, TimelineEntityTypes.CUSTOMER, customerId,
                    "Dispute opened", null,
                    TimelineEntityTypes.DISPUTE, disputeId));
        }
        return rows;
    }

    // ── Risk events ───────────────────────────────────────────────────────────

    private List<TimelineEvent> mapRiskDecision(DomainEvent event) {
        Long piId = extractLong(event, "paymentIntentId");
        if (piId == null) return Collections.emptyList();

        String decision = extractString(event, "decision");
        String title    = decision != null ? "Risk decision: " + decision : "Risk decision made";
        return List.of(entry(event, TimelineEntityTypes.PAYMENT_INTENT, piId,
                title, null, null, null));
    }

    // ── Builder helpers ───────────────────────────────────────────────────────

    private TimelineEvent entry(
            DomainEvent event,
            String entityType, Long entityId,
            String title, String summary,
            String relatedEntityType, Long relatedEntityId) {

        return TimelineEvent.builder()
                .merchantId(event.getMerchantId())
                .entityType(entityType)
                .entityId(entityId)
                .eventType(event.getEventType())
                .eventTime(event.getCreatedAt())
                .title(title)
                .summary(summary)
                .relatedEntityType(relatedEntityType)
                .relatedEntityId(relatedEntityId)
                .correlationId(event.getCorrelationId())
                .causationId(event.getCausationId())
                .payloadPreviewJson(truncate(event.getPayload()))
                .sourceEventId(event.getId())
                .build();
    }

    private String buildFailureSummary(String gateway, String category) {
        if (gateway == null && category == null) return null;
        StringBuilder sb = new StringBuilder();
        if (gateway  != null) sb.append("Gateway: ").append(gateway);
        if (gateway  != null && category != null) sb.append("; ");
        if (category != null) sb.append("Reason: ").append(category);
        return sb.toString();
    }

    // ── Payload extraction ────────────────────────────────────────────────────

    private Long extractLong(DomainEvent event, String field) {
        try {
            JsonNode node = objectMapper.readTree(event.getPayload());
            JsonNode val  = node.get(field);
            return (val != null && !val.isNull()) ? val.asLong() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String extractString(DomainEvent event, String field) {
        try {
            JsonNode node = objectMapper.readTree(event.getPayload());
            JsonNode val  = node.get(field);
            return (val != null && !val.isNull()) ? val.asText() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= PAYLOAD_PREVIEW_MAX ? s : s.substring(0, PAYLOAD_PREVIEW_MAX);
    }
}
