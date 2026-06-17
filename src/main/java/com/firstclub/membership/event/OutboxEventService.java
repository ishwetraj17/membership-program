package com.firstclub.membership.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.membership.entity.OutboxEvent;
import com.firstclub.membership.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Writes domain events to the transactional outbox and (in-process) publishes them via
 * Spring's {@link ApplicationEventPublisher}, plus a relay that dispatches PENDING rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxEventService {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @PostConstruct
    void registerMetrics() {
        // Backlog gauge — alert when the relay falls behind.
        meterRegistry.gauge("membership.outbox.pending", outboxRepository,
                r -> (double) r.countByStatus(OutboxEvent.Status.PENDING));
    }

    /**
     * Records a domain event durably (outbox, same transaction as the caller) and publishes
     * it in-process. In-process {@code @TransactionalEventListener(AFTER_COMMIT)} consumers
     * therefore only fire if the surrounding transaction commits.
     */
    public void publish(String aggregateType, Long aggregateId, String eventType, SubscriptionDomainEvent event) {
        outboxRepository.save(OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(serialize(event))
                .status(OutboxEvent.Status.PENDING)
                .createdAt(LocalDateTime.now(clock))
                .build());
        eventPublisher.publishEvent(event);
    }

    private static final int MAX_RETRIES = 5;

    /**
     * Relay step. Each PENDING event is atomically claimed via a conditional update, so with
     * multiple application instances an event is dispatched exactly once (no SELECT … FOR UPDATE
     * needed). A failed publish is requeued with an incremented retry count, and moved to DEAD
     * once it exceeds {@link #MAX_RETRIES}.
     */
    @Transactional
    public int dispatchPending() {
        List<OutboxEvent> batch = outboxRepository.findTop100ByStatusOrderByCreatedAtAsc(OutboxEvent.Status.PENDING);
        int dispatched = 0;
        for (OutboxEvent event : batch) {
            if (outboxRepository.claim(event.getId(), LocalDateTime.now(clock)) == 0) {
                continue; // another node already claimed it
            }
            try {
                // Production: publish to Kafka/SNS/etc. Demo: log to represent the broker hand-off.
                log.info("Outbox dispatch — id={} type={} aggregate={}:{}",
                        event.getId(), event.getEventType(), event.getAggregateType(), event.getAggregateId());
                meterRegistry.counter("membership.outbox.dispatched").increment();
                dispatched++;
            } catch (Exception ex) {
                int attempts = event.getRetryCount() + 1;
                if (attempts >= MAX_RETRIES) {
                    outboxRepository.markDead(event.getId(), attempts);
                    log.error("Outbox event {} moved to DEAD after {} attempts", event.getId(), attempts, ex);
                } else {
                    outboxRepository.requeue(event.getId(), attempts);
                    log.warn("Outbox event {} requeued (attempt {})", event.getId(), attempts, ex);
                }
            }
        }
        return dispatched;
    }

    /** Retention: delete dispatched events older than the cutoff. */
    @Transactional
    public int purgeDispatched(LocalDateTime cutoff) {
        return outboxRepository.deleteDispatchedBefore(cutoff);
    }

    /** Dead-letter events for inspection. */
    @Transactional(readOnly = true)
    public List<OutboxEvent> deadLetters() {
        return outboxRepository.findTop100ByStatusOrderByIdAsc(OutboxEvent.Status.DEAD);
    }

    /** Requeue all dead-letter events; the relay re-attempts them on its next run. */
    @Transactional
    public int replayDead() {
        return outboxRepository.replayDead();
    }

    private String serialize(SubscriptionDomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}
