package com.firstclub.events.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.events.dto.EventMetadataDTO;
import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.event.DomainEventRecordedEvent;
import com.firstclub.events.repository.DomainEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Append-only domain event log.
 *
 * <p>Each call to {@link #record} writes one row to {@code domain_events}.
 * The table is never updated or deleted — it is an immutable audit trail.
 *
 * <p>V29 overloads accept {@link EventMetadataDTO} to capture event versioning,
 * correlation IDs, aggregate tags, and the owning merchant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DomainEventLog {

    private final DomainEventRepository  domainEventRepository;
    private final ObjectMapper            objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    // ── Original API (backwards-compatible) ──────────────────────────────────

    /**
     * Write an event with a pre-serialised JSON payload string.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public DomainEvent record(String eventType, String jsonPayload) {
        return record(eventType, jsonPayload, null);
    }

    /**
     * Convenience overload — serialises the payload map then delegates.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public DomainEvent record(String eventType, Map<String, Object> payload) {
        return record(eventType, payload, null);
    }

    // ── V29 metadata-aware API ────────────────────────────────────────────────

    /**
     * Record an event with metadata (versioning, correlation, aggregate, merchant).
     *
     * <p>If {@code metadata} is null, defaults are applied (version=1, correlationId from MDC).
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public DomainEvent record(String eventType, String jsonPayload, EventMetadataDTO metadata) {
        DomainEvent.DomainEventBuilder builder = DomainEvent.builder()
                .eventType(eventType)
                .payload(jsonPayload);

        applyMetadata(builder, metadata);

        DomainEvent event = domainEventRepository.save(builder.build());
        eventPublisher.publishEvent(new DomainEventRecordedEvent(this, event));
        log.debug("Domain event recorded: type={} id={} merchant={} aggregate={}:{}",
                eventType, event.getId(), event.getMerchantId(),
                event.getAggregateType(), event.getAggregateId());
        return event;
    }

    /**
     * Record an event from a Map payload with metadata.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public DomainEvent record(String eventType, Map<String, Object> payload, EventMetadataDTO metadata) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            return record(eventType, json, metadata);
        } catch (Exception ex) {
            log.error("Failed to serialise domain event payload for type {}", eventType, ex);
            throw new IllegalStateException("Domain event serialisation failure", ex);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applyMetadata(DomainEvent.DomainEventBuilder builder, EventMetadataDTO m) {
        // Always attempt to pick up correlationId from MDC (set by request filter)
        String mdcCorrelationId = MDC.get("requestId");

        if (m == null) {
            if (mdcCorrelationId != null) {
                builder.correlationId(mdcCorrelationId);
            }
            return;
        }

        builder.eventVersion(m.getEventVersion())
               .schemaVersion(m.getSchemaVersion())
               .causationId(m.getCausationId())
               .aggregateType(m.getAggregateType())
               .aggregateId(m.getAggregateId())
               .merchantId(m.getMerchantId());

        // Prefer explicit correlationId; fall back to MDC
        String corrId = m.getCorrelationId() != null ? m.getCorrelationId() : mdcCorrelationId;
        builder.correlationId(corrId);
    }
}
