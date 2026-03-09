package com.firstclub.platform.integrity;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.repository.DomainEventRepository;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionSchedule;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.platform.integrity.checks.events.DomainEventMetadataChecker;
import com.firstclub.platform.integrity.checks.recon.BatchUniquenessChecker;
import com.firstclub.platform.integrity.checks.revenue.ScheduleTotalEqualsInvoiceAmountChecker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for revenue, recon, and events integrity checkers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Revenue / Recon / Events Integrity Checkers — Unit Tests")
class RevenueReconEventsIntegrityCheckerTest {

    // ── ScheduleTotalEqualsInvoiceAmountChecker ───────────────────────────────

    @Nested
    @DisplayName("ScheduleTotalEqualsInvoiceAmountChecker")
    class ScheduleTotalTests {

        @Mock private RevenueRecognitionScheduleRepository scheduleRepository;
        @Mock private InvoiceRepository invoiceRepository;
        @InjectMocks
        private ScheduleTotalEqualsInvoiceAmountChecker checker;

        @Test
        @DisplayName("PASS when schedule total equals invoice grand total")
        void pass_whenScheduleTotalMatchesInvoice() {
            Invoice invoice = Invoice.builder()
                    .id(1L).merchantId(10L)
                    .invoiceNumber("INV-500")
                    .grandTotal(new BigDecimal("360.00"))
                    .build();

            when(invoiceRepository.findByMerchantId(10L)).thenReturn(List.of(invoice));
            when(scheduleRepository.findByInvoiceId(1L)).thenReturn(List.of(
                    schedule(1L, new BigDecimal("120.00")),
                    schedule(2L, new BigDecimal("120.00")),
                    schedule(3L, new BigDecimal("120.00"))
            ));

            assertThat(checker.run(10L).isPassed()).isTrue();
        }

        @Test
        @DisplayName("FAIL when schedule total does not match invoice grand total")
        void fail_whenMismatch() {
            Invoice invoice = Invoice.builder()
                    .id(2L).merchantId(10L)
                    .invoiceNumber("INV-501")
                    .grandTotal(new BigDecimal("360.00"))
                    .build();

            when(invoiceRepository.findByMerchantId(10L)).thenReturn(List.of(invoice));
            when(scheduleRepository.findByInvoiceId(2L)).thenReturn(List.of(
                    schedule(4L, new BigDecimal("100.00")),
                    schedule(5L, new BigDecimal("100.00"))  // only 200 ≠ 360
            ));

            IntegrityCheckResult result = checker.run(10L);
            assertThat(result.isPassed()).isFalse();
            assertThat(result.getViolationCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("PASS when invoice has no revenue schedules (skipped)")
        void pass_whenNoSchedules() {
            Invoice invoice = Invoice.builder()
                    .id(3L).merchantId(10L)
                    .grandTotal(new BigDecimal("500.00"))
                    .build();

            when(invoiceRepository.findByMerchantId(10L)).thenReturn(List.of(invoice));
            when(scheduleRepository.findByInvoiceId(3L)).thenReturn(List.of());

            assertThat(checker.run(10L).isPassed()).isTrue();
        }

        @Test
        @DisplayName("invariant key and severity are correct")
        void invariantKeyAndSeverity() {
            assertThat(checker.getInvariantKey()).isEqualTo("revenue.schedule_total_equals_invoice_amount");
            assertThat(checker.getSeverity()).isEqualTo(IntegrityCheckSeverity.HIGH);
        }
    }

    // ── BatchUniquenessChecker ────────────────────────────────────────────────

    @Nested
    @DisplayName("BatchUniquenessChecker")
    class BatchUniquenessTests {

        @Mock private EntityManager entityManager;
        @SuppressWarnings("rawtypes")
        @Mock private TypedQuery batchQuery;

        private BatchUniquenessChecker checker;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
            checker = new BatchUniquenessChecker();
            ReflectionTestUtils.setField(checker, "entityManager", entityManager);

            when(entityManager.createQuery(anyString(), eq(Object[].class)))
                    .thenReturn(batchQuery);
            when(batchQuery.setParameter(anyString(), any())).thenReturn(batchQuery);
        }

        @Test
        @DisplayName("PASS when no duplicate batches exist")
        @SuppressWarnings("unchecked")
        void pass_whenNoDuplicateBatches() {
            when(batchQuery.getResultList()).thenReturn(List.of());

            assertThat(checker.run(null).isPassed()).isTrue();
        }

        @Test
        @DisplayName("FAIL when duplicate (merchantId, batchDate) found")
        @SuppressWarnings("unchecked")
        void fail_whenDuplicateBatchExists() {
            // Simulate a GROUP BY row: [merchantId=1, batchDate=date, count=2]
            Object[] duplicateRow = {1L, java.time.LocalDate.of(2024, 3, 15), 2L};
            when(batchQuery.getResultList()).thenReturn(List.<Object[]>of(duplicateRow));

            IntegrityCheckResult result = checker.run(null);
            assertThat(result.isPassed()).isFalse();
            assertThat(result.getViolationCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("PASS when no duplicate batches for merchant scope")
        @SuppressWarnings("unchecked")
        void pass_whenMerchantScopedNoDuplicates() {
            when(batchQuery.getResultList()).thenReturn(List.of());

            assertThat(checker.run(99L).isPassed()).isTrue();
        }

        @Test
        @DisplayName("invariant key and severity are correct")
        void invariantKeyAndSeverity() {
            assertThat(checker.getInvariantKey()).isEqualTo("recon.batch_uniqueness");
            assertThat(checker.getSeverity()).isEqualTo(IntegrityCheckSeverity.HIGH);
        }
    }

    // ── DomainEventMetadataChecker ────────────────────────────────────────────

    @Nested
    @DisplayName("DomainEventMetadataChecker")
    class DomainEventMetadataTests {

        @Mock private DomainEventRepository domainEventRepository;
        @InjectMocks
        private DomainEventMetadataChecker checker;

        @Test
        @DisplayName("PASS when all recent events have required metadata")
        void pass_whenAllMetadataPresent() {
            DomainEvent event = DomainEvent.builder()
                    .id(1L)
                    .eventType("SUBSCRIPTION_ACTIVATED")
                    .aggregateType("Subscription")
                    .aggregateId("42")
                    .payload("{}")
                    .build();

            when(domainEventRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(any(), any()))
                    .thenReturn(List.of(event));

            assertThat(checker.run(null).isPassed()).isTrue();
        }

        @Test
        @DisplayName("FAIL when event is missing aggregateType")
        void fail_whenMissingAggregateType() {
            DomainEvent event = DomainEvent.builder()
                    .id(2L)
                    .eventType("PAYMENT_CAPTURED")
                    .aggregateType(null)  // missing
                    .aggregateId("99")
                    .payload("{}")
                    .build();

            when(domainEventRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(any(), any()))
                    .thenReturn(List.of(event));

            IntegrityCheckResult result = checker.run(null);
            assertThat(result.isPassed()).isFalse();
            assertThat(result.getViolationCount()).isEqualTo(1);
            assertThat(result.getViolations().get(0).getEntityId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("FAIL when event has blank eventType")
        void fail_whenBlankEventType() {
            DomainEvent event = DomainEvent.builder()
                    .id(3L)
                    .eventType("   ")  // blank
                    .aggregateType("Invoice")
                    .aggregateId("10")
                    .payload("{}")
                    .build();

            when(domainEventRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(any(), any()))
                    .thenReturn(List.of(event));

            assertThat(checker.run(null).isPassed()).isFalse();
        }

        @Test
        @DisplayName("PASS when no events in lookback window")
        void pass_whenNoEvents() {
            when(domainEventRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(any(), any()))
                    .thenReturn(List.of());

            assertThat(checker.run(null).isPassed()).isTrue();
        }

        @Test
        @DisplayName("invariant key and severity are correct")
        void invariantKeyAndSeverity() {
            assertThat(checker.getInvariantKey()).isEqualTo("events.metadata_populated");
            assertThat(checker.getSeverity()).isEqualTo(IntegrityCheckSeverity.MEDIUM);
        }
    }

    // ── Test data helpers ────────────────────────────────────────────────────

    private static RevenueRecognitionSchedule schedule(Long id, BigDecimal amount) {
        return RevenueRecognitionSchedule.builder().id(id).amount(amount).build();
    }
}
