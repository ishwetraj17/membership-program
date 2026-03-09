package com.firstclub.platform.integrity;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.entity.InvoiceLine;
import com.firstclub.billing.entity.InvoiceLineType;
import com.firstclub.billing.repository.InvoiceLineRepository;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.platform.integrity.checks.billing.DiscountTotalConsistencyChecker;
import com.firstclub.platform.integrity.checks.billing.InvoicePeriodOverlapChecker;
import com.firstclub.platform.integrity.checks.billing.InvoiceTotalEqualsLineSumChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for billing integrity checkers.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Billing Integrity Checkers — Unit Tests")
class BillingIntegrityCheckerTest {

    // ── InvoiceTotalEqualsLineSumChecker ─────────────────────────────────────

    @Nested
    @DisplayName("InvoiceTotalEqualsLineSumChecker")
    class InvoiceTotalTests {

        @Mock private InvoiceRepository     invoiceRepository;
        @Mock private InvoiceLineRepository invoiceLineRepository;

        @InjectMocks
        private InvoiceTotalEqualsLineSumChecker checker;

        @Test
        @DisplayName("PASS when invoice grand total matches sum of lines")
        void pass_whenGrandTotalEqualsLineSum() {
            Invoice invoice = Invoice.builder()
                    .id(1L).merchantId(10L)
                    .invoiceNumber("INV-001")
                    .grandTotal(new BigDecimal("300.00"))
                    .build();

            when(invoiceRepository.findByMerchantId(10L)).thenReturn(List.of(invoice));
            when(invoiceLineRepository.findByInvoiceId(1L)).thenReturn(List.of(
                    line(1L, new BigDecimal("200.00")),
                    line(2L, new BigDecimal("100.00"))
            ));

            IntegrityCheckResult result = checker.run(10L);

            assertThat(result.isPassed()).isTrue();
            assertThat(result.getViolationCount()).isZero();
        }

        @Test
        @DisplayName("FAIL when invoice grand total does not match line sum")
        void fail_whenGrandTotalMismatch() {
            Invoice invoice = Invoice.builder()
                    .id(2L).merchantId(10L)
                    .invoiceNumber("INV-002")
                    .grandTotal(new BigDecimal("500.00"))
                    .build();

            when(invoiceRepository.findByMerchantId(10L)).thenReturn(List.of(invoice));
            when(invoiceLineRepository.findByInvoiceId(2L)).thenReturn(List.of(
                    line(3L, new BigDecimal("200.00")) // only 200 but grand total is 500
            ));

            IntegrityCheckResult result = checker.run(10L);

            assertThat(result.isPassed()).isFalse();
            assertThat(result.getViolationCount()).isEqualTo(1);
            assertThat(result.getViolations().get(0).getEntityId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("PASS when no invoices found")
        void pass_whenNoInvoicesFound() {
            when(invoiceRepository.findByMerchantId(99L)).thenReturn(List.of());

            IntegrityCheckResult result = checker.run(99L);

            assertThat(result.isPassed()).isTrue();
        }

        @Test
        @DisplayName("invariant key and severity are correct")
        void invariantKeyAndSeverity() {
            assertThat(checker.getInvariantKey()).isEqualTo("billing.invoice_total_equals_line_sum");
            assertThat(checker.getSeverity()).isEqualTo(IntegrityCheckSeverity.CRITICAL);
        }
    }

    // ── DiscountTotalConsistencyChecker ──────────────────────────────────────

    @Nested
    @DisplayName("DiscountTotalConsistencyChecker")
    class DiscountTotalTests {

        @Mock private InvoiceRepository     invoiceRepository;
        @Mock private InvoiceLineRepository invoiceLineRepository;

        @InjectMocks
        private DiscountTotalConsistencyChecker checker;

        @Test
        @DisplayName("PASS when discountTotal matches absolute sum of DISCOUNT lines")
        void pass_whenDiscountTotalMatches() {
            Invoice invoice = Invoice.builder()
                    .id(10L).merchantId(5L)
                    .invoiceNumber("INV-010")
                    .discountTotal(new BigDecimal("50.00"))
                    .grandTotal(new BigDecimal("450.00"))
                    .build();

            when(invoiceRepository.findByMerchantId(5L)).thenReturn(List.of(invoice));
            when(invoiceLineRepository.findByInvoiceId(10L)).thenReturn(List.of(
                    discountLine(20L, new BigDecimal("-50.00"))  // negative amount
            ));

            assertThat(checker.run(5L).isPassed()).isTrue();
        }

        @Test
        @DisplayName("FAIL when discountTotal does not match DISCOUNT line sum")
        void fail_whenDiscountMismatch() {
            Invoice invoice = Invoice.builder()
                    .id(11L).merchantId(5L)
                    .invoiceNumber("INV-011")
                    .discountTotal(new BigDecimal("100.00"))
                    .grandTotal(new BigDecimal("400.00"))
                    .build();

            when(invoiceRepository.findByMerchantId(5L)).thenReturn(List.of(invoice));
            when(invoiceLineRepository.findByInvoiceId(11L)).thenReturn(List.of(
                    discountLine(21L, new BigDecimal("-30.00"))  // 30 != 100
            ));

            IntegrityCheckResult result = checker.run(5L);
            assertThat(result.isPassed()).isFalse();
            assertThat(result.getViolationCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("PASS when invoice has no discount lines and discountTotal is zero")
        void pass_whenNoDiscountLinesAndZeroTotal() {
            Invoice invoice = Invoice.builder()
                    .id(12L).merchantId(5L)
                    .invoiceNumber("INV-012")
                    .grandTotal(new BigDecimal("200.00"))
                    .build();
            // discountTotal defaults to BigDecimal.ZERO via @Builder.Default

            when(invoiceRepository.findByMerchantId(5L)).thenReturn(List.of(invoice));
            when(invoiceLineRepository.findByInvoiceId(12L)).thenReturn(List.of(
                    line(22L, new BigDecimal("200.00"))  // no DISCOUNT line
            ));

            assertThat(checker.run(5L).isPassed()).isTrue();
        }
    }

    // ── InvoicePeriodOverlapChecker ──────────────────────────────────────────

    @Nested
    @DisplayName("InvoicePeriodOverlapChecker")
    class PeriodOverlapTests {

        @Mock private InvoiceRepository invoiceRepository;

        @InjectMocks
        private InvoicePeriodOverlapChecker checker;

        @Test
        @DisplayName("PASS when invoice periods for a subscription do not overlap")
        void pass_whenNoOverlap() {
            LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
            Invoice i1 = Invoice.builder().id(1L).subscriptionId(5L).merchantId(1L)
                    .periodStart(base).periodEnd(base.plusMonths(1))
                    .grandTotal(BigDecimal.ONE).build();
            Invoice i2 = Invoice.builder().id(2L).subscriptionId(5L).merchantId(1L)
                    .periodStart(base.plusMonths(1)).periodEnd(base.plusMonths(2))
                    .grandTotal(BigDecimal.ONE).build();

            when(invoiceRepository.findByMerchantId(1L)).thenReturn(List.of(i1, i2));

            assertThat(checker.run(1L).isPassed()).isTrue();
        }

        @Test
        @DisplayName("FAIL when two invoices for same subscription have overlapping periods")
        void fail_whenOverlapFound() {
            LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
            Invoice i1 = Invoice.builder().id(1L).subscriptionId(5L).merchantId(1L)
                    .periodStart(base).periodEnd(base.plusDays(20))
                    .grandTotal(BigDecimal.ONE).build();
            Invoice i2 = Invoice.builder().id(2L).subscriptionId(5L).merchantId(1L)
                    .periodStart(base.plusDays(10)).periodEnd(base.plusDays(30)) // overlaps
                    .grandTotal(BigDecimal.ONE).build();

            when(invoiceRepository.findByMerchantId(1L)).thenReturn(List.of(i1, i2));

            IntegrityCheckResult result = checker.run(1L);
            assertThat(result.isPassed()).isFalse();
            assertThat(result.getViolationCount()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("PASS when invoices have no subscriptionId")
        void pass_whenNoSubscriptionId() {
            Invoice orphan = Invoice.builder().id(3L).merchantId(1L)
                    .periodStart(LocalDateTime.now()).periodEnd(LocalDateTime.now().plusDays(30))
                    .grandTotal(BigDecimal.ONE).build();

            when(invoiceRepository.findByMerchantId(1L)).thenReturn(List.of(orphan));

            assertThat(checker.run(1L).isPassed()).isTrue();
        }
    }

    // ── Test data helpers ────────────────────────────────────────────────────

    private static InvoiceLine line(Long id, BigDecimal amount) {
        return InvoiceLine.builder().id(id).lineType(InvoiceLineType.PLAN_CHARGE).amount(amount).build();
    }

    private static InvoiceLine discountLine(Long id, BigDecimal amount) {
        return InvoiceLine.builder().id(id).lineType(InvoiceLineType.DISCOUNT).amount(amount).build();
    }
}
