package com.firstclub.outbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.handler.OutboxEventHandler;
import com.firstclub.outbox.handler.OutboxEventHandlerRegistry;
import com.firstclub.outbox.lease.OutboxLeaseRecoveryService;
import com.firstclub.outbox.ordering.OutboxPrioritySelector;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.payments.entity.DeadLetterMessage;
import com.firstclub.payments.repository.DeadLetterMessageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxService Unit Tests")
class OutboxServiceTest {

    @Mock private OutboxEventRepository       outboxEventRepository;
    @Mock private DeadLetterMessageRepository  deadLetterRepository;
    @Mock private OutboxEventHandlerRegistry   handlerRegistry;
    @Mock private MeterRegistry               meterRegistry;
    @Mock private Counter                     outboxFailedCounterMock;
    @Spy  private ObjectMapper                objectMapper = new ObjectMapper();
    @Mock private OutboxPrioritySelector      prioritySelector;
    @Mock private OutboxLeaseRecoveryService  leaseRecoveryService;

    @InjectMocks
    private OutboxService outboxService;

    @BeforeEach
    void initCounters() {
        // Mockito does not call @PostConstruct; wire the counter manually
        when(meterRegistry.counter("outbox_failed_total")).thenReturn(outboxFailedCounterMock);
        outboxService.init();
    }

    // -------------------------------------------------------------------------
    // publish()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("publish()")
    class Publish {

        @Test
        @DisplayName("persists an OutboxEvent with status=NEW and serialized payload")
        void publish_persistsNewEvent() {
            ArgumentCaptor<OutboxEvent> cap = ArgumentCaptor.forClass(OutboxEvent.class);
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            outboxService.publish("INVOICE_CREATED", Map.of("invoiceId", 42L));

            verify(outboxEventRepository).save(cap.capture());
            OutboxEvent saved = cap.getValue();
            assertThat(saved.getEventType()).isEqualTo("INVOICE_CREATED");
            assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.NEW);
            assertThat(saved.getAttempts()).isZero();
            assertThat(saved.getNextAttemptAt()).isNotNull();
            assertThat(saved.getPayload()).contains("42");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when payload is not serializable")
        void publish_unserializablePayload_throws() {
            // A non-bean object (Thread) that Jackson cannot serialize
            assertThatThrownBy(() -> outboxService.publish("X", new Object() {
                @SuppressWarnings("unused") public final Object circular = this; // circular ref
            })).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -------------------------------------------------------------------------
    // lockDueEvents()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("lockDueEvents()")
    class LockDueEvents {

        @Test
        @DisplayName("returns IDs after marking events PROCESSING")
        void lockDueEvents_marksProcessing() {
            OutboxEvent e1 = OutboxEvent.builder().id(1L).status(OutboxEventStatus.NEW)
                    .nextAttemptAt(LocalDateTime.now().minusSeconds(5)).build();
            OutboxEvent e2 = OutboxEvent.builder().id(2L).status(OutboxEventStatus.NEW)
                    .nextAttemptAt(LocalDateTime.now().minusSeconds(5)).build();

            when(prioritySelector.selectAndLockBatch(10)).thenReturn(List.of(e1, e2));
            when(outboxEventRepository.saveAll(anyList())).thenReturn(List.of(e1, e2));

            List<Long> ids = outboxService.lockDueEvents(10);

            assertThat(ids).containsExactly(1L, 2L);
            assertThat(e1.getStatus()).isEqualTo(OutboxEventStatus.PROCESSING);
            assertThat(e2.getStatus()).isEqualTo(OutboxEventStatus.PROCESSING);
        }

        @Test
        @DisplayName("returns empty list when no due events exist")
        void lockDueEvents_empty_returnsEmptyList() {
            when(prioritySelector.selectAndLockBatch(anyInt())).thenReturn(List.of());

            assertThat(outboxService.lockDueEvents(10)).isEmpty();
            verify(outboxEventRepository, never()).saveAll(any());
        }
    }

    // -------------------------------------------------------------------------
    // processSingleEvent()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("processSingleEvent()")
    class ProcessSingleEvent {

        private OutboxEvent event;
        private OutboxEventHandler handler;

        @BeforeEach
        void setUp() {
            event = OutboxEvent.builder()
                    .id(100L)
                    .eventType("INVOICE_CREATED")
                    .payload("{\"invoiceId\":42}")
                    .status(OutboxEventStatus.PROCESSING)
                    .attempts(0)
                    .nextAttemptAt(LocalDateTime.now())
                    .build();

            handler = mock(OutboxEventHandler.class);
            // getEventType() is called by the registry, not by OutboxService directly,
            // so we do NOT stub it here to avoid UnnecessaryStubbing errors.
        }

        @Test
        @DisplayName("successful handler → status DONE")
        void success_marksDone() throws Exception {
            when(outboxEventRepository.findById(100L)).thenReturn(Optional.of(event));
            when(handlerRegistry.resolve("INVOICE_CREATED")).thenReturn(Optional.of(handler));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            outboxService.processSingleEvent(100L);

            ArgumentCaptor<OutboxEvent> cap = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(OutboxEventStatus.DONE);
            assertThat(cap.getValue().getLastError()).isNull();
        }

        @Test
        @DisplayName("handler throws → attempts++ and status stays NEW for retry")
        void failure_incrementsAttemptsAndSchedulesRetry() throws Exception {
            doThrow(new RuntimeException("downstream error")).when(handler).handle(any());
            when(outboxEventRepository.findById(100L)).thenReturn(Optional.of(event));
            when(handlerRegistry.resolve("INVOICE_CREATED")).thenReturn(Optional.of(handler));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            outboxService.processSingleEvent(100L);

            ArgumentCaptor<OutboxEvent> cap = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(cap.capture());
            OutboxEvent saved = cap.getValue();
            assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.NEW);
            assertThat(saved.getAttempts()).isEqualTo(1);
            assertThat(saved.getNextAttemptAt()).isAfter(LocalDateTime.now().minusSeconds(1));
            assertThat(saved.getLastError()).contains("downstream error");
            verifyNoInteractions(deadLetterRepository);
        }

        @Test
        @DisplayName("handler throws on MAX_ATTEMPTS-th failure → status FAILED and DLQ written")
        void maxAttempts_failsAndWritesDLQ() throws Exception {
            event.setAttempts(OutboxService.MAX_ATTEMPTS - 1);  // one more failure → terminal
            doThrow(new RuntimeException("fatal")).when(handler).handle(any());
            when(outboxEventRepository.findById(100L)).thenReturn(Optional.of(event));
            when(handlerRegistry.resolve("INVOICE_CREATED")).thenReturn(Optional.of(handler));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(deadLetterRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            outboxService.processSingleEvent(100L);

            ArgumentCaptor<OutboxEvent> eventCap = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(eventCap.capture());
            assertThat(eventCap.getValue().getStatus()).isEqualTo(OutboxEventStatus.FAILED);
            assertThat(eventCap.getValue().getAttempts()).isEqualTo(OutboxService.MAX_ATTEMPTS);

            ArgumentCaptor<DeadLetterMessage> dlqCap = ArgumentCaptor.forClass(DeadLetterMessage.class);
            verify(deadLetterRepository).save(dlqCap.capture());
            assertThat(dlqCap.getValue().getSource()).isEqualTo("OUTBOX");
            assertThat(dlqCap.getValue().getPayload()).contains("INVOICE_CREATED");
            assertThat(dlqCap.getValue().getError()).contains("fatal");
        }

        @Test
        @DisplayName("unknown event type → status DONE (no retry loop)")
        void unknownEventType_marksDoneWithoutHandler() {
            when(outboxEventRepository.findById(100L)).thenReturn(Optional.of(event));
            when(handlerRegistry.resolve("INVOICE_CREATED")).thenReturn(Optional.empty());
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            outboxService.processSingleEvent(100L);

            ArgumentCaptor<OutboxEvent> cap = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(OutboxEventStatus.DONE);
            verifyNoInteractions(deadLetterRepository);
        }

        @Test
        @DisplayName("event not found → no-op (no save, no handler)")
        void eventNotFound_noOp() {
            when(outboxEventRepository.findById(999L)).thenReturn(Optional.empty());

            outboxService.processSingleEvent(999L);

            verify(outboxEventRepository, never()).save(any());
            verifyNoInteractions(handlerRegistry, deadLetterRepository);
        }
    }
}
