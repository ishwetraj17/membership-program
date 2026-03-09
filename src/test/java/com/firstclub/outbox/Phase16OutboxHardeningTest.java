package com.firstclub.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.handler.OutboxEventHandler;
import com.firstclub.outbox.handler.OutboxEventHandlerRegistry;
import com.firstclub.outbox.lease.OutboxLeaseRecoveryService;
import com.firstclub.outbox.ordering.OutboxPrioritySelector;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.outbox.service.OutboxService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 16 — Outbox and DLQ Hardening Unit Tests.
 *
 * <ul>
 *   <li>Stale lease recovery: stuck PROCESSING events are reset to NEW.</li>
 *   <li>Failure categorization: exception type maps to correct category string.</li>
 *   <li>DLQ propagation: {@code writeToDLQ} forwards failureCategory and merchantId.</li>
 *   <li>Lease stamps: lockDueEvents sets processingStartedAt and processingOwner.</li>
 *   <li>Lease clearance: successful processSingleEvent clears lease stamp fields.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 16 — Outbox Hardening Tests")
class Phase16OutboxHardeningTest {

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
        when(meterRegistry.counter("outbox_failed_total")).thenReturn(outboxFailedCounterMock);
        outboxService.init();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Stale Lease Recovery
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("recoverStaleLeases()")
    class RecoverStaleLeases {

        @Test
        @DisplayName("resets stale PROCESSING events to NEW and returns their count")
        void resetsStaleEventsToNew() {
            when(leaseRecoveryService.recoverAll(OutboxService.STALE_LEASE_MINUTES)).thenReturn(2);

            int recovered = outboxService.recoverStaleLeases();

            assertThat(recovered).isEqualTo(2);
        }

        @Test
        @DisplayName("returns 0 and makes no saveAll call when no stale events")
        void returnsZero_whenNoStaleEvents() {
            when(leaseRecoveryService.recoverAll(anyInt())).thenReturn(0);

            int recovered = outboxService.recoverStaleLeases();

            assertThat(recovered).isZero();
            verify(outboxEventRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("threshold is STALE_LEASE_MINUTES before now")
        void usesCorrectStaleLeasThreshold() {
            when(leaseRecoveryService.recoverAll(anyInt())).thenReturn(0);

            outboxService.recoverStaleLeases();

            verify(leaseRecoveryService).recoverAll(OutboxService.STALE_LEASE_MINUTES);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Failure Category Classification
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("categorizeFailure() static helper")
    class CategorizeFailure {

        @Test
        @DisplayName("Jackson exception → PAYLOAD_PARSE_ERROR")
        void jacksonException_payloadParseError() {
            Exception ex = new com.fasterxml.jackson.core.JsonParseException(null, "bad json");
            assertThat(OutboxService.categorizeFailure(ex)).isEqualTo("PAYLOAD_PARSE_ERROR");
        }

        @Test
        @DisplayName("message containing 'duplicate' → DEDUP_DUPLICATE")
        void duplicateMessage_dedupDuplicate() {
            Exception ex = new RuntimeException("duplicate key value violates constraint");
            assertThat(OutboxService.categorizeFailure(ex)).isEqualTo("DEDUP_DUPLICATE");
        }

        @Test
        @DisplayName("message containing 'ledger' → ACCOUNTING_ERROR")
        void ledgerMessage_accountingError() {
            Exception ex = new RuntimeException("ledger account not found");
            assertThat(OutboxService.categorizeFailure(ex)).isEqualTo("ACCOUNTING_ERROR");
        }

        @Test
        @DisplayName("message containing 'balance' → BUSINESS_RULE_VIOLATION")
        void balanceMessage_businessRuleViolation() {
            Exception ex = new RuntimeException("insufficient balance for refund");
            assertThat(OutboxService.categorizeFailure(ex)).isEqualTo("BUSINESS_RULE_VIOLATION");
        }

        @Test
        @DisplayName("message containing 'timeout' → TRANSIENT_ERROR")
        void timeoutMessage_transientError() {
            Exception ex = new RuntimeException("connection timeout after 5000ms");
            assertThat(OutboxService.categorizeFailure(ex)).isEqualTo("TRANSIENT_ERROR");
        }

        @Test
        @DisplayName("unrecognized exception → UNKNOWN")
        void randomException_unknown() {
            Exception ex = new RuntimeException("some random internal error");
            assertThat(OutboxService.categorizeFailure(ex)).isEqualTo("UNKNOWN");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // DLQ Propagation: failureCategory and merchantId
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("writeToDLQ() propagation (via processSingleEvent MAX_ATTEMPTS exceeded)")
    class DlqPropagation {

        @Test
        @DisplayName("DLQ record receives failureCategory and merchantId from the outbox event")
        void dlqRecord_receivesFailureCategoryAndMerchantId() throws Exception {
            OutboxEvent event = OutboxEvent.builder()
                    .id(10L)
                    .eventType("INVOICE_CREATED")
                    .payload("{\"id\":10}")
                    .status(OutboxEventStatus.PROCESSING)
                    .attempts(OutboxService.MAX_ATTEMPTS - 1) // one more failure → FAILED
                    .merchantId(42L)
                    .nextAttemptAt(LocalDateTime.now())
                    .build();

            OutboxEventHandler failingHandler = mock(OutboxEventHandler.class);
            doThrow(new RuntimeException("connection timeout")).when(failingHandler).handle(event);
            when(outboxEventRepository.findById(10L)).thenReturn(Optional.of(event));
            when(handlerRegistry.resolve("INVOICE_CREATED")).thenReturn(Optional.of(failingHandler));
            when(deadLetterRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            outboxService.processSingleEvent(10L);

            ArgumentCaptor<DeadLetterMessage> dlqCaptor = ArgumentCaptor.forClass(DeadLetterMessage.class);
            verify(deadLetterRepository).save(dlqCaptor.capture());
            DeadLetterMessage dlq = dlqCaptor.getValue();

            assertThat(dlq.getFailureCategory()).isEqualTo("TRANSIENT_ERROR");
            assertThat(dlq.getMerchantId()).isEqualTo(42L);
            assertThat(dlq.getPayload()).startsWith("INVOICE_CREATED|");
        }

        @Test
        @DisplayName("failureCategory is set on the OutboxEvent row on first failure")
        void failureCategory_setOnEventOnFirstFailure() throws Exception {
            OutboxEvent event = OutboxEvent.builder()
                    .id(11L)
                    .eventType("PAYMENT_SUCCEEDED")
                    .payload("{\"id\":11}")
                    .status(OutboxEventStatus.PROCESSING)
                    .attempts(0)
                    .nextAttemptAt(LocalDateTime.now())
                    .build();

            OutboxEventHandler failingHandler = mock(OutboxEventHandler.class);
            doThrow(new RuntimeException("duplicate key")).when(failingHandler).handle(event);
            when(outboxEventRepository.findById(11L)).thenReturn(Optional.of(event));
            when(handlerRegistry.resolve("PAYMENT_SUCCEEDED")).thenReturn(Optional.of(failingHandler));

            outboxService.processSingleEvent(11L);

            assertThat(event.getFailureCategory()).isEqualTo("DEDUP_DUPLICATE");
            assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.NEW); // retry
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lease Stamps
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("lockDueEvents() — lease stamping")
    class LeaseStamping {

        @Test
        @DisplayName("stamps processingStartedAt and processingOwner on locked events")
        void stampsLeaseFields() {
            OutboxEvent e1 = OutboxEvent.builder().id(1L).status(OutboxEventStatus.NEW).build();
            OutboxEvent e2 = OutboxEvent.builder().id(2L).status(OutboxEventStatus.NEW).build();
            when(prioritySelector.selectAndLockBatch(50)).thenReturn(List.of(e1, e2));

            outboxService.lockDueEvents(50);

            assertThat(e1.getStatus()).isEqualTo(OutboxEventStatus.PROCESSING);
            assertThat(e1.getProcessingStartedAt()).isNotNull();
            assertThat(e1.getProcessingOwner()).isNotNull().isNotBlank();

            assertThat(e2.getStatus()).isEqualTo(OutboxEventStatus.PROCESSING);
            assertThat(e2.getProcessingOwner()).isEqualTo(e1.getProcessingOwner()); // same JVM
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lease Clearance on Success
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("processSingleEvent() — lease clearance on success")
    class LeaseClearance {

        @Test
        @DisplayName("clears processingStartedAt and processingOwner after successful dispatch")
        void clearsLeaseFieldsOnSuccess() throws Exception {
            OutboxEvent event = OutboxEvent.builder()
                    .id(20L)
                    .eventType("SUBSCRIPTION_ACTIVATED")
                    .payload("{\"sub\":\"abc\"}")
                    .status(OutboxEventStatus.PROCESSING)
                    .attempts(0)
                    .processingOwner("host:99")
                    .processingStartedAt(LocalDateTime.now().minusSeconds(5))
                    .nextAttemptAt(LocalDateTime.now())
                    .build();

            OutboxEventHandler okHandler = mock(OutboxEventHandler.class);
            doNothing().when(okHandler).handle(event);
            when(outboxEventRepository.findById(20L)).thenReturn(Optional.of(event));
            when(handlerRegistry.resolve("SUBSCRIPTION_ACTIVATED")).thenReturn(Optional.of(okHandler));

            outboxService.processSingleEvent(20L);

            assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.DONE);
            assertThat(event.getProcessingOwner()).isNull();
            assertThat(event.getProcessingStartedAt()).isNull();
        }
    }
}
