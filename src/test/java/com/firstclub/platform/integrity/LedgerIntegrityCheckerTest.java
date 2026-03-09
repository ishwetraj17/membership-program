package com.firstclub.platform.integrity;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerLine;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.repository.LedgerLineRepository;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionSchedule;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.platform.integrity.checks.ledger.LedgerEntryBalancedChecker;
import com.firstclub.platform.integrity.checks.ledger.RevenueRecognitionCeilingChecker;
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
 * Unit tests for ledger integrity checkers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Ledger Integrity Checkers — Unit Tests")
class LedgerIntegrityCheckerTest {

    // ── LedgerEntryBalancedChecker ────────────────────────────────────────────

    @Nested
    @DisplayName("LedgerEntryBalancedChecker")
    class BalancedCheckerTests {

        @Mock private LedgerLineRepository ledgerLineRepository;
        @Mock private EntityManager entityManager;
        @SuppressWarnings("rawtypes")
        @Mock private TypedQuery entryQuery;

        private LedgerEntryBalancedChecker checker;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
            checker = new LedgerEntryBalancedChecker(ledgerLineRepository);
            ReflectionTestUtils.setField(checker, "entityManager", entityManager);

            when(entityManager.createQuery(anyString(), eq(LedgerEntry.class)))
                    .thenReturn(entryQuery);
            when(entryQuery.setParameter(anyString(), any())).thenReturn(entryQuery);
            when(entryQuery.setMaxResults(anyInt())).thenReturn(entryQuery);
        }

        @Test
        @DisplayName("PASS when debit sum equals credit sum for all entries")
        @SuppressWarnings("unchecked")
        void pass_whenAllEntriesBalanced() {
            LedgerEntry entry = LedgerEntry.builder().id(1L).build();
            when(entryQuery.getResultList()).thenReturn(List.of(entry));
            when(ledgerLineRepository.findByEntryId(1L)).thenReturn(List.of(
                    ledgerLine(1L, LineDirection.DEBIT,  new BigDecimal("100.00")),
                    ledgerLine(2L, LineDirection.CREDIT, new BigDecimal("100.00"))
            ));

            assertThat(checker.run(null).isPassed()).isTrue();
        }

        @Test
        @DisplayName("FAIL when debit sum does not equal credit sum")
        @SuppressWarnings("unchecked")
        void fail_whenEntryIsImbalanced() {
            LedgerEntry entry = LedgerEntry.builder().id(2L).build();
            when(entryQuery.getResultList()).thenReturn(List.of(entry));
            when(ledgerLineRepository.findByEntryId(2L)).thenReturn(List.of(
                    ledgerLine(3L, LineDirection.DEBIT,  new BigDecimal("500.00")),
                    ledgerLine(4L, LineDirection.CREDIT, new BigDecimal("300.00"))  // imbalanced
            ));

            IntegrityCheckResult result = checker.run(null);
            assertThat(result.isPassed()).isFalse();
            assertThat(result.getViolations().get(0).getEntityId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("PASS when no entries found in lookback window")
        @SuppressWarnings("unchecked")
        void pass_whenNoEntries() {
            when(entryQuery.getResultList()).thenReturn(List.of());

            assertThat(checker.run(null).isPassed()).isTrue();
        }

        @Test
        @DisplayName("invariant key and severity are correct")
        void invariantKeyAndSeverity() {
            assertThat(checker.getInvariantKey()).isEqualTo("ledger.entry_balanced");
            assertThat(checker.getSeverity()).isEqualTo(IntegrityCheckSeverity.CRITICAL);
        }
    }

    // ── RevenueRecognitionCeilingChecker ──────────────────────────────────────

    @Nested
    @DisplayName("RevenueRecognitionCeilingChecker")
    class CeilingCheckerTests {

        @Mock private RevenueRecognitionScheduleRepository scheduleRepository;
        @Mock private InvoiceRepository invoiceRepository;
        @InjectMocks
        private RevenueRecognitionCeilingChecker checker;

        @Test
        @DisplayName("PASS when total scheduled amount is within invoice grand total")
        void pass_whenScheduleTotalWithinCeiling() {
            Invoice invoice = Invoice.builder()
                    .id(1L).merchantId(5L)
                    .grandTotal(new BigDecimal("1200.00"))
                    .invoiceNumber("INV-100")
                    .build();

            when(invoiceRepository.findByMerchantId(5L)).thenReturn(List.of(invoice));
            when(scheduleRepository.findByInvoiceId(1L)).thenReturn(List.of(
                    schedule(1L, new BigDecimal("600.00")),
                    schedule(2L, new BigDecimal("600.00"))
            ));

            assertThat(checker.run(5L).isPassed()).isTrue();
        }

        @Test
        @DisplayName("FAIL when total scheduled amount exceeds invoice grand total")
        void fail_whenOverRecognition() {
            Invoice invoice = Invoice.builder()
                    .id(2L).merchantId(5L)
                    .grandTotal(new BigDecimal("1000.00"))
                    .invoiceNumber("INV-200")
                    .build();

            when(invoiceRepository.findByMerchantId(5L)).thenReturn(List.of(invoice));
            when(scheduleRepository.findByInvoiceId(2L)).thenReturn(List.of(
                    schedule(3L, new BigDecimal("700.00")),
                    schedule(4L, new BigDecimal("500.00"))  // total 1200 > 1000
            ));

            IntegrityCheckResult result = checker.run(5L);
            assertThat(result.isPassed()).isFalse();
            assertThat(result.getViolationCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("PASS when invoice has no schedules")
        void pass_whenNoSchedules() {
            Invoice invoice = Invoice.builder()
                    .id(3L).merchantId(5L)
                    .grandTotal(new BigDecimal("500.00"))
                    .build();

            when(invoiceRepository.findByMerchantId(5L)).thenReturn(List.of(invoice));
            when(scheduleRepository.findByInvoiceId(3L)).thenReturn(List.of());

            assertThat(checker.run(5L).isPassed()).isTrue();
        }
    }

    // ── Test data helpers ────────────────────────────────────────────────────

    private static LedgerLine ledgerLine(Long id, LineDirection dir, BigDecimal amount) {
        return LedgerLine.builder().id(id).direction(dir).amount(amount).build();
    }

    private static RevenueRecognitionSchedule schedule(Long id, BigDecimal amount) {
        return RevenueRecognitionSchedule.builder().id(id).amount(amount).build();
    }
}
