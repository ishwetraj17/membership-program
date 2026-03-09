package com.firstclub.recon;

import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentAttemptStatus;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.recon.classification.ReconExpectation;
import com.firstclub.recon.classification.ReconExpectationClassifier;
import com.firstclub.recon.classification.ReconSeverity;
import com.firstclub.recon.entity.MismatchType;
import com.firstclub.recon.entity.ReconMismatch;
import com.firstclub.recon.entity.ReconMismatchStatus;
import com.firstclub.recon.gateway.OrphanGatewayPaymentChecker;
import com.firstclub.recon.mismatch.DuplicateSettlementChecker;
import com.firstclub.recon.repository.DuplicateBatchProjection;
import com.firstclub.recon.repository.ReconMismatchRepository;
import com.firstclub.recon.repository.SettlementBatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 14 — Reconciliation mismatch taxonomy unit tests.
 *
 * <p>Tests cover:
 * <ol>
 *   <li>{@link ReconWindowPolicy} timing math</li>
 *   <li>{@link ReconExpectationClassifier} classification rules</li>
 *   <li>{@link OrphanGatewayPaymentChecker} orphan detection</li>
 *   <li>{@link DuplicateSettlementChecker} duplicate batch detection</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 14 — Reconciliation Mismatch Taxonomy")
class Phase14ReconTaxonomyTests {

    // =========================================================================
    // ReconWindowPolicy
    // =========================================================================

    @Nested
    @DisplayName("ReconWindowPolicy")
    class ReconWindowPolicyTests {

        @Test
        @DisplayName("default window is 30 minutes on both sides of the day")
        void defaultWindow_is30Minutes() {
            ReconWindowPolicy policy = new ReconWindowPolicy(30);
            LocalDate date = LocalDate.of(2026, 3, 9);
            ReconWindowPolicy.ReconWindow window = policy.windowFor(date);

            assertThat(window.extendedStart())
                    .isEqualTo(LocalDateTime.of(2026, 3, 9, 0, 0, 0).minusMinutes(30));
            assertThat(window.strictStart())
                    .isEqualTo(LocalDateTime.of(2026, 3, 9, 0, 0, 0));
            assertThat(window.extendedEnd())
                    .isEqualTo(LocalDateTime.of(2026, 3, 9, 23, 59, 59, 999_999_999).plusMinutes(30));
        }

        @Test
        @DisplayName("timestamp just before midnight is near boundary")
        void timestampJustBeforeMidnight_isNearBoundary() {
            ReconWindowPolicy policy = new ReconWindowPolicy(30);
            LocalDate date = LocalDate.of(2026, 3, 10);
            // 23:45 on March 9 is within 30 min BEFORE the strict start of March 10
            LocalDateTime ts = LocalDateTime.of(2026, 3, 9, 23, 45);

            assertThat(policy.isNearBoundary(date, ts)).isTrue();
        }

        @Test
        @DisplayName("timestamp well inside the day is not near boundary")
        void timestampMiddleOfDay_notNearBoundary() {
            ReconWindowPolicy policy = new ReconWindowPolicy(30);
            LocalDate date = LocalDate.of(2026, 3, 9);
            LocalDateTime ts = LocalDateTime.of(2026, 3, 9, 14, 0);

            assertThat(policy.isNearBoundary(date, ts)).isFalse();
        }

        @Test
        @DisplayName("zero-minute window uses exact day boundaries")
        void zeroMinuteWindow_usesExactBoundaries() {
            ReconWindowPolicy policy = new ReconWindowPolicy(0);
            LocalDate date = LocalDate.of(2026, 3, 9);
            ReconWindowPolicy.ReconWindow window = policy.windowFor(date);

            assertThat(window.extendedStart()).isEqualTo(window.strictStart());
            assertThat(window.extendedEnd()).isEqualTo(window.strictEnd());
        }

        @Test
        @DisplayName("negative window minutes throws IllegalArgumentException")
        void negativeWindowMinutes_throws() {
            assertThatThrownBy(() -> new ReconWindowPolicy(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("-1");
        }
    }

    // =========================================================================
    // ReconExpectationClassifier
    // =========================================================================

    @Nested
    @DisplayName("ReconExpectationClassifier")
    class ReconExpectationClassifierTests {

        private final ReconExpectationClassifier classifier = new ReconExpectationClassifier();

        @Test
        @DisplayName("INVOICE_NO_PAYMENT near day boundary → EXPECTED_TIMING_DIFFERENCE / WARNING")
        void invoiceNoPayment_nearBoundary_classifiedExpected() {
            ReconExpectationClassifier.ClassificationResult result =
                    classifier.classify(MismatchType.INVOICE_NO_PAYMENT, true);

            assertThat(result.expectation()).isEqualTo(ReconExpectation.EXPECTED_TIMING_DIFFERENCE);
            assertThat(result.severity()).isEqualTo(ReconSeverity.WARNING);
        }

        @Test
        @DisplayName("INVOICE_NO_PAYMENT NOT near boundary → UNEXPECTED_SYSTEM_ERROR / WARNING")
        void invoiceNoPayment_notNearBoundary_classifiedUnexpected() {
            ReconExpectationClassifier.ClassificationResult result =
                    classifier.classify(MismatchType.INVOICE_NO_PAYMENT, false);

            assertThat(result.expectation()).isEqualTo(ReconExpectation.UNEXPECTED_SYSTEM_ERROR);
            assertThat(result.severity()).isEqualTo(ReconSeverity.WARNING);
        }

        @Test
        @DisplayName("ORPHAN_GATEWAY_PAYMENT → UNEXPECTED_GATEWAY_ERROR / CRITICAL")
        void orphanGatewayPayment_classifiedCritical() {
            ReconExpectationClassifier.ClassificationResult result =
                    classifier.classify(MismatchType.ORPHAN_GATEWAY_PAYMENT, false);

            assertThat(result.expectation()).isEqualTo(ReconExpectation.UNEXPECTED_GATEWAY_ERROR);
            assertThat(result.severity()).isEqualTo(ReconSeverity.CRITICAL);
        }

        @Test
        @DisplayName("DUPLICATE_SETTLEMENT → UNEXPECTED_SYSTEM_ERROR / CRITICAL")
        void duplicateSettlement_classifiedCritical() {
            ReconExpectationClassifier.ClassificationResult result =
                    classifier.classify(MismatchType.DUPLICATE_SETTLEMENT, false);

            assertThat(result.expectation()).isEqualTo(ReconExpectation.UNEXPECTED_SYSTEM_ERROR);
            assertThat(result.severity()).isEqualTo(ReconSeverity.CRITICAL);
        }

        @Test
        @DisplayName("AMOUNT_MISMATCH → UNEXPECTED_SYSTEM_ERROR / CRITICAL")
        void amountMismatch_classifiedCritical() {
            ReconExpectationClassifier.ClassificationResult result =
                    classifier.classify(MismatchType.AMOUNT_MISMATCH, false);

            assertThat(result.expectation()).isEqualTo(ReconExpectation.UNEXPECTED_SYSTEM_ERROR);
            assertThat(result.severity()).isEqualTo(ReconSeverity.CRITICAL);
        }

        @Test
        @DisplayName("PAYMENT_NO_INVOICE (not near boundary) → UNEXPECTED_GATEWAY_ERROR / WARNING")
        void paymentNoInvoice_notNearBoundary_gatewayWarning() {
            ReconExpectationClassifier.ClassificationResult result =
                    classifier.classify(MismatchType.PAYMENT_NO_INVOICE, false);

            assertThat(result.expectation()).isEqualTo(ReconExpectation.UNEXPECTED_GATEWAY_ERROR);
            assertThat(result.severity()).isEqualTo(ReconSeverity.WARNING);
        }
    }

    // =========================================================================
    // OrphanGatewayPaymentChecker
    // =========================================================================

    @Nested
    @DisplayName("OrphanGatewayPaymentChecker")
    class OrphanGatewayPaymentCheckerTests {

        @Mock
        private PaymentAttemptRepository attemptRepository;
        @Mock
        private ReconMismatchRepository mismatchRepository;

        @InjectMocks
        private OrphanGatewayPaymentChecker checker;

        @Test
        @DisplayName("creates ORPHAN_GATEWAY_PAYMENT mismatch when succeeded attempt has no invoice")
        void orphanedAttempt_createsMismatch() {
            PaymentIntentV2 intent = PaymentIntentV2.builder()
                    .id(99L)
                    .build();
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .id(7L)
                    .paymentIntent(intent)
                    .status(PaymentAttemptStatus.SUCCEEDED)
                    .gatewayTransactionId("gtxn-abc-123")
                    .build();

            when(attemptRepository.findSucceededWithGatewayTxnAndNoInvoice())
                    .thenReturn(List.of(attempt));

            ArgumentCaptor<ReconMismatch> captor = ArgumentCaptor.forClass(ReconMismatch.class);
            when(mismatchRepository.save(captor.capture()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            List<ReconMismatch> results = checker.check(42L);

            assertThat(results).hasSize(1);
            ReconMismatch saved = captor.getValue();
            assertThat(saved.getType()).isEqualTo(MismatchType.ORPHAN_GATEWAY_PAYMENT);
            assertThat(saved.getSeverity()).isEqualTo(ReconSeverity.CRITICAL);
            assertThat(saved.getExpectation()).isEqualTo(ReconExpectation.UNEXPECTED_GATEWAY_ERROR);
            assertThat(saved.getGatewayTransactionId()).isEqualTo("gtxn-abc-123");
            assertThat(saved.getReportId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("returns empty list when no orphaned attempts exist")
        void noOrphanedAttempts_returnsEmpty() {
            when(attemptRepository.findSucceededWithGatewayTxnAndNoInvoice())
                    .thenReturn(List.of());

            List<ReconMismatch> results = checker.check(1L);

            assertThat(results).isEmpty();
            verify(mismatchRepository, never()).save(any());
        }
    }

    // =========================================================================
    // DuplicateSettlementChecker
    // =========================================================================

    @Nested
    @DisplayName("DuplicateSettlementChecker")
    class DuplicateSettlementCheckerTests {

        @Mock
        private SettlementBatchRepository batchRepository;
        @Mock
        private ReconMismatchRepository mismatchRepository;

        @InjectMocks
        private DuplicateSettlementChecker checker;

        @Test
        @DisplayName("creates DUPLICATE_SETTLEMENT mismatch when two batches exist for same merchant+date")
        void duplicateBatches_createsMismatch() {
            LocalDate date = LocalDate.of(2026, 3, 9);
            DuplicateBatchProjection proj = mockProjection(101L, 2L);

            when(batchRepository.findDuplicateMerchantBatchesForDate(date))
                    .thenReturn(List.of(proj));

            ArgumentCaptor<ReconMismatch> captor = ArgumentCaptor.forClass(ReconMismatch.class);
            when(mismatchRepository.save(captor.capture()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            List<ReconMismatch> results = checker.check(date, 55L);

            assertThat(results).hasSize(1);
            ReconMismatch saved = captor.getValue();
            assertThat(saved.getType()).isEqualTo(MismatchType.DUPLICATE_SETTLEMENT);
            assertThat(saved.getSeverity()).isEqualTo(ReconSeverity.CRITICAL);
            assertThat(saved.getExpectation()).isEqualTo(ReconExpectation.UNEXPECTED_SYSTEM_ERROR);
            assertThat(saved.getMerchantId()).isEqualTo(101L);
            assertThat(saved.getReportId()).isEqualTo(55L);
        }

        @Test
        @DisplayName("returns empty list when no duplicate batches")
        void noDuplicates_returnsEmpty() {
            LocalDate date = LocalDate.of(2026, 3, 9);
            when(batchRepository.findDuplicateMerchantBatchesForDate(date))
                    .thenReturn(List.of());

            List<ReconMismatch> results = checker.check(date, 1L);

            assertThat(results).isEmpty();
            verify(mismatchRepository, never()).save(any());
        }

        private DuplicateBatchProjection mockProjection(Long merchantId, long count) {
            return new DuplicateBatchProjection() {
                @Override public Long getMerchantId() { return merchantId; }
                @Override public long getBatchCount()  { return count; }
            };
        }
    }
}
