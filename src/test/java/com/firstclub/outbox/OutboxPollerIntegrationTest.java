package com.firstclub.outbox;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.handler.OutboxEventHandler;
import com.firstclub.outbox.handler.OutboxEventHandlerRegistry;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.outbox.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration tests verifying the full outbox poller lifecycle.
 *
 * <p>The {@link OutboxEventHandlerRegistry} is replaced by a {@link MockBean}
 * that returns a no-op handler (success) so we can control outcomes precisely.
 *
 * <h3>Test 1 — publish within a transaction → event persisted as NEW</h3>
 * <h3>Test 2 — poller processes the event → status becomes DONE</h3>
 */
class OutboxPollerIntegrationTest extends PostgresIntegrationTestBase {

    @MockitoBean
    private OutboxEventHandlerRegistry handlerRegistry;

    @Autowired private OutboxService         outboxService;
    @Autowired private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        // Clean up events from previous tests
        outboxEventRepository.deleteAll();
    }

    // -------------------------------------------------------------------------
    // Test 1: publish writes event in NEW state
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("publish() writes an OutboxEvent with status=NEW in the same transaction")
    void publish_writesNewEventToDatabase() {
        // Arrange: outbox event written via OutboxService (called within @Transactional in service,
        // but we call it directly here — OutboxService uses propagation=REQUIRED so creates its own TX)
        outboxService.publish("TEST_EVENT", java.util.Map.of("key", "value"));

        // Assert
        var events = outboxEventRepository.findByStatus(OutboxEventStatus.NEW);
        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.getEventType()).isEqualTo("TEST_EVENT");
        assertThat(event.getPayload()).contains("value");
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getNextAttemptAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }

    // -------------------------------------------------------------------------
    // Test 2: poller delivers event → DONE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("poller processes a NEW event and marks it DONE when handler succeeds")
    void poller_processesEvent_marksDone() {
        // Arrange: a handler that always succeeds (no-op)
        OutboxEventHandler noopHandler = new OutboxEventHandler() {
            @Override public String getEventType() { return "TEST_EVENT"; }
            @Override public void handle(OutboxEvent event) { /* no-op */ }
        };
        when(handlerRegistry.resolve(any())).thenReturn(Optional.of(noopHandler));

        // Seed an event that is due now
        OutboxEvent event = outboxEventRepository.save(OutboxEvent.builder()
                .eventType("TEST_EVENT")
                .payload("{\"key\":\"val\"}")
                .status(OutboxEventStatus.NEW)
                .attempts(0)
                .nextAttemptAt(LocalDateTime.now().minusSeconds(5))
                .build());

        // Act: lock + process (simulates one scheduler tick)
        java.util.List<Long> ids = outboxService.lockDueEvents(10);
        assertThat(ids).contains(event.getId());
        outboxService.processSingleEvent(event.getId());

        // Assert
        OutboxEvent processed = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(OutboxEventStatus.DONE);
    }

    // -------------------------------------------------------------------------
    // Test 3: event not due yet is not picked up by poller
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("poller skips events whose next_attempt_at is in the future")
    void poller_skipsEventNotYetDue() {
        outboxEventRepository.save(OutboxEvent.builder()
                .eventType("FUTURE_EVENT")
                .payload("{}")
                .status(OutboxEventStatus.NEW)
                .attempts(0)
                .nextAttemptAt(LocalDateTime.now().plusHours(1))   // future
                .build());

        java.util.List<Long> ids = outboxService.lockDueEvents(10);

        assertThat(ids).isEmpty();
    }
}
