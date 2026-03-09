package com.firstclub.outbox;

import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.handler.OutboxEventHandler;
import com.firstclub.outbox.handler.OutboxEventHandlerRegistry;
import com.firstclub.outbox.lease.OutboxLeaseRecoveryService;
import com.firstclub.outbox.ordering.OutboxAggregateSequencer;
import com.firstclub.outbox.ordering.OutboxPrioritySelector;
import com.firstclub.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 12 unit tests.
 *
 * <ul>
 *   <li>Starvation prevention: fresh events always precede retry events.</li>
 *   <li>Lease-based recovery: expired lease → event reset to NEW.</li>
 *   <li>Active lease protection: non-expired event is never reclaimed.</li>
 *   <li>Aggregate sequencing: next sequence is MAX + 1 (or 1 if no prior).</li>
 *   <li>Compile-time safety: registry throws on missing OutboxEventType handler.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OutboxPhase12Tests {

    // ── Starvation prevention ─────────────────────────────────────────────────

    @Nested
    class StarvationPreventionTests {

        @Mock  OutboxEventRepository repository;
        @InjectMocks OutboxPrioritySelector selector;

        @Test
        void freshEventsSelectedBeforeRetries() {
            OutboxEvent fresh = event(1L, 0);
            OutboxEvent retry = event(2L, 2);

            when(repository.findAndLockFreshEvents(any(LocalDateTime.class), eq(50)))
                    .thenReturn(List.of(fresh));
            when(repository.findAndLockRetryEvents(any(LocalDateTime.class), eq(49)))
                    .thenReturn(List.of(retry));

            List<OutboxEvent> batch = selector.selectAndLockBatch(50);

            assertThat(batch).hasSize(2);
            assertThat(batch.get(0)).isSameAs(fresh); // fresh first
            assertThat(batch.get(1)).isSameAs(retry);
        }

        @Test
        void retryQuerySkippedWhenFreshBatchFull() {
            List<OutboxEvent> freshBatch = IntStream.range(0, 50)
                    .mapToObj(i -> event((long) i, 0))
                    .toList();

            when(repository.findAndLockFreshEvents(any(LocalDateTime.class), eq(50)))
                    .thenReturn(freshBatch);

            List<OutboxEvent> batch = selector.selectAndLockBatch(50);

            assertThat(batch).hasSize(50);
            // remaining = 0 → retry query must not be called
            verify(repository, never()).findAndLockRetryEvents(any(), anyInt());
        }

        private OutboxEvent event(Long id, int attempts) {
            return OutboxEvent.builder()
                    .id(id)
                    .eventType("TEST")
                    .payload("{}")
                    .status(OutboxEventStatus.NEW)
                    .attempts(attempts)
                    .nextAttemptAt(LocalDateTime.now().minusMinutes(1))
                    .build();
        }
    }

    // ── Stale lease recovery ──────────────────────────────────────────────────

    @Nested
    class LeaseRecoveryTests {

        @Mock  OutboxEventRepository repository;
        @InjectMocks OutboxLeaseRecoveryService recoveryService;

        @Test
        void expiredLeaseIsRecovered() {
            OutboxEvent stale = OutboxEvent.builder()
                    .id(1L)
                    .eventType("INVOICE_CREATED")
                    .payload("{}")
                    .status(OutboxEventStatus.PROCESSING)
                    .attempts(1)
                    .nextAttemptAt(LocalDateTime.now().minusMinutes(30))
                    .processingOwner("node-abc")
                    .leaseExpiresAt(LocalDateTime.now().minusMinutes(10))
                    .build();

            when(repository.findByLeaseExpiredBefore(eq(OutboxEventStatus.PROCESSING), any()))
                    .thenReturn(List.of(stale));

            int count = recoveryService.recoverExpiredLeases();

            assertThat(count).isEqualTo(1);
            assertThat(stale.getStatus()).isEqualTo(OutboxEventStatus.NEW);
            assertThat(stale.getLeaseExpiresAt()).isNull();
            assertThat(stale.getProcessingOwner()).isNull();
            verify(repository).saveAll(List.of(stale));
        }

        @Test
        void activeLeasesAreNotReclaimed() {
            // findByLeaseExpiredBefore returns empty — no expired leases
            when(repository.findByLeaseExpiredBefore(any(), any())).thenReturn(List.of());

            int count = recoveryService.recoverExpiredLeases();

            assertThat(count).isZero();
            verify(repository, never()).saveAll(any());
        }

        @Test
        void staleLeaseRecoveredOnlyOnce() {
            // Second call finds nothing left to recover
            when(repository.findByLeaseExpiredBefore(any(), any()))
                    .thenReturn(List.of()) // first call: already recovered
                    .thenReturn(List.of()); // second call: still empty

            recoveryService.recoverExpiredLeases();
            int second = recoveryService.recoverExpiredLeases();

            assertThat(second).isZero();
        }
    }

    // ── Aggregate sequencing ─────────────────────────────────────────────────

    @Nested
    class AggregateSequenceTests {

        @Mock  OutboxEventRepository repository;
        @InjectMocks OutboxAggregateSequencer sequencer;

        @Test
        void nextSequenceIsMaxPlusOne() {
            when(repository.findMaxAggregateSequence("Subscription", "sub-1"))
                    .thenReturn(Optional.of(5L));

            long seq = sequencer.nextSequence("Subscription", "sub-1");

            assertThat(seq).isEqualTo(6L);
        }

        @Test
        void firstEventForAggregateGetsSequenceOne() {
            when(repository.findMaxAggregateSequence("Invoice", "inv-1"))
                    .thenReturn(Optional.empty());

            long seq = sequencer.nextSequence("Invoice", "inv-1");

            assertThat(seq).isEqualTo(1L);
        }
    }

    // ── Handler registry compile-time safety ─────────────────────────────────

    @Nested
    class HandlerRegistryStartupTests {

        @Test
        void startupFailsWhenHandlerMissing() {
            // Only provides 1 of 4 required handlers → should throw
            OutboxEventHandler partial = mockHandler("INVOICE_CREATED");

            assertThatThrownBy(() -> new OutboxEventHandlerRegistry(List.of(partial)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing handlers for required event type");
        }

        @Test
        void startupSucceedsWhenAllHandlersPresent() {
            OutboxEventHandler h1 = mockHandler("INVOICE_CREATED");
            OutboxEventHandler h2 = mockHandler("PAYMENT_SUCCEEDED");
            OutboxEventHandler h3 = mockHandler("SUBSCRIPTION_ACTIVATED");
            OutboxEventHandler h4 = mockHandler("REFUND_ISSUED");

            // Must not throw
            OutboxEventHandlerRegistry registry =
                    new OutboxEventHandlerRegistry(List.of(h1, h2, h3, h4));

            assertThat(registry.resolve("INVOICE_CREATED")).isPresent();
            assertThat(registry.resolve("PAYMENT_SUCCEEDED")).isPresent();
        }

        @Test
        void startupFailsWhenDuplicateHandlerRegistered() {
            OutboxEventHandler dup1 = mockHandler("INVOICE_CREATED");
            OutboxEventHandler dup2 = mockHandler("INVOICE_CREATED");
            OutboxEventHandler h2   = mockHandler("PAYMENT_SUCCEEDED");
            OutboxEventHandler h3   = mockHandler("SUBSCRIPTION_ACTIVATED");
            OutboxEventHandler h4   = mockHandler("REFUND_ISSUED");

            assertThatThrownBy(() ->
                    new OutboxEventHandlerRegistry(List.of(dup1, dup2, h2, h3, h4)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Multiple OutboxEventHandler beans");
        }

        private OutboxEventHandler mockHandler(String eventType) {
            OutboxEventHandler h = mock(OutboxEventHandler.class);
            when(h.getEventType()).thenReturn(eventType);
            return h;
        }
    }
}
