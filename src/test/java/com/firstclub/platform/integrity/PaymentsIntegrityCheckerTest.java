package com.firstclub.platform.integrity;

import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentAttemptStatus;
import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.refund.entity.RefundV2Status;
import com.firstclub.payments.refund.repository.RefundV2Repository;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.platform.integrity.checks.payments.RefundWithinRefundableAmountChecker;
import com.firstclub.platform.integrity.checks.payments.TerminalIntentNoNewAttemptsChecker;
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
 * Unit tests for payments integrity checkers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Payments Integrity Checkers — Unit Tests")
class PaymentsIntegrityCheckerTest {

    // ── RefundWithinRefundableAmountChecker ───────────────────────────────────

    @Nested
    @DisplayName("RefundWithinRefundableAmountChecker")
    class RefundCheckerTests {

        @Mock private PaymentRepository  paymentRepository;
        @Mock private RefundV2Repository refundV2Repository;
        @InjectMocks
        private RefundWithinRefundableAmountChecker checker;

        @Test
        @DisplayName("PASS when refunds are within captured amount")
        void pass_whenRefundsWithinCapturedAmount() {
            Payment payment = Payment.builder()
                    .id(1L).merchantId(10L)
                    .capturedAmount(new BigDecimal("500.00"))
                    .gatewayTxnId("TXN-001")
                    .currency("INR")
                    .build();

            when(paymentRepository.findByMerchantId(10L)).thenReturn(List.of(payment));
            when(refundV2Repository.sumAmountByPaymentIdAndStatus(1L, RefundV2Status.COMPLETED))
                    .thenReturn(new BigDecimal("200.00"));

            IntegrityCheckResult result = checker.run(10L);

            assertThat(result.isPassed()).isTrue();
            assertThat(result.getViolationCount()).isZero();
        }

        @Test
        @DisplayName("FAIL when refunds exceed captured amount (over-refund)")
        void fail_whenOverRefund() {
            Payment payment = Payment.builder()
                    .id(2L).merchantId(10L)
                    .capturedAmount(new BigDecimal("100.00"))
                    .gatewayTxnId("TXN-002")
                    .currency("INR")
                    .build();

            when(paymentRepository.findByMerchantId(10L)).thenReturn(List.of(payment));
            when(refundV2Repository.sumAmountByPaymentIdAndStatus(2L, RefundV2Status.COMPLETED))
                    .thenReturn(new BigDecimal("150.00"));  // over-refund

            IntegrityCheckResult result = checker.run(10L);

            assertThat(result.isPassed()).isFalse();
            assertThat(result.getViolationCount()).isEqualTo(1);
            assertThat(result.getViolations().get(0).getEntityId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("PASS when refundSumAmount is null (no completed refunds)")
        void pass_whenNoCompletedRefunds() {
            Payment payment = Payment.builder()
                    .id(3L).merchantId(10L)
                    .capturedAmount(new BigDecimal("300.00"))
                    .build();

            when(paymentRepository.findByMerchantId(10L)).thenReturn(List.of(payment));
            when(refundV2Repository.sumAmountByPaymentIdAndStatus(3L, RefundV2Status.COMPLETED))
                    .thenReturn(null);  // no refunds

            assertThat(checker.run(10L).isPassed()).isTrue();
        }

        @Test
        @DisplayName("invariant key and severity are correct")
        void invariantKeyAndSeverity() {
            assertThat(checker.getInvariantKey()).isEqualTo("payments.refund_within_refundable_amount");
            assertThat(checker.getSeverity()).isEqualTo(IntegrityCheckSeverity.CRITICAL);
        }
    }

    // ── TerminalIntentNoNewAttemptsChecker ────────────────────────────────────

    @Nested
    @DisplayName("TerminalIntentNoNewAttemptsChecker")
    class TerminalIntentTests {

        @Mock private PaymentAttemptRepository attemptRepository;
        @Mock private EntityManager entityManager;
        @SuppressWarnings("rawtypes")
        @Mock private TypedQuery intentQuery;

        private TerminalIntentNoNewAttemptsChecker checker;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
            checker = new TerminalIntentNoNewAttemptsChecker(attemptRepository);
            ReflectionTestUtils.setField(checker, "entityManager", entityManager);

            when(entityManager.createQuery(anyString(), eq(PaymentIntentV2.class)))
                    .thenReturn(intentQuery);
            when(intentQuery.setParameter(anyString(), any())).thenReturn(intentQuery);
            when(intentQuery.setMaxResults(anyInt())).thenReturn(intentQuery);
        }

        @Test
        @DisplayName("PASS when terminal intents have no in-progress attempts")
        @SuppressWarnings("unchecked")
        void pass_whenNoStuckAttempts() {
            PaymentIntentV2 intent = PaymentIntentV2.builder()
                    .id(1L).status(PaymentIntentStatusV2.SUCCEEDED).build();
            when(intentQuery.getResultList()).thenReturn(List.of(intent));

            PaymentAttempt completedAttempt = PaymentAttempt.builder()
                    .id(1L).attemptNumber(1).build();
            completedAttempt.setStatus(PaymentAttemptStatus.CAPTURED);
            when(attemptRepository.findByPaymentIntentIdOrderByAttemptNumberAsc(1L))
                    .thenReturn(List.of(completedAttempt));

            IntegrityCheckResult result = checker.run(null);

            assertThat(result.isPassed()).isTrue();
        }

        @Test
        @DisplayName("FAIL when terminal intent has a STARTED attempt")
        @SuppressWarnings("unchecked")
        void fail_whenTerminalIntentHasStartedAttempt() {
            PaymentIntentV2 intent = PaymentIntentV2.builder()
                    .id(2L).status(PaymentIntentStatusV2.FAILED).build();
            when(intentQuery.getResultList()).thenReturn(List.of(intent));

            PaymentAttempt stuckAttempt = PaymentAttempt.builder()
                    .id(2L).attemptNumber(1).build();
            // Default status is STARTED
            when(attemptRepository.findByPaymentIntentIdOrderByAttemptNumberAsc(2L))
                    .thenReturn(List.of(stuckAttempt));

            IntegrityCheckResult result = checker.run(null);

            assertThat(result.isPassed()).isFalse();
            assertThat(result.getViolationCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("PASS when no terminal intents found")
        @SuppressWarnings("unchecked")
        void pass_whenNoTerminalIntents() {
            when(intentQuery.getResultList()).thenReturn(List.of());

            assertThat(checker.run(null).isPassed()).isTrue();
        }

        @Test
        @DisplayName("invariant key and severity are correct")
        void invariantKeyAndSeverity() {
            assertThat(checker.getInvariantKey()).isEqualTo("payments.terminal_intent_no_active_attempts");
            assertThat(checker.getSeverity()).isEqualTo(IntegrityCheckSeverity.HIGH);
        }
    }
}
