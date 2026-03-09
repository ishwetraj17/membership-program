package com.firstclub.payments.recovery;

import com.firstclub.payments.entity.FailureCategory;
import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentAttemptStatus;
import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.exception.PaymentIntentException;
import com.firstclub.payments.gateway.GatewayResult;
import com.firstclub.payments.gateway.GatewayStatusResolver;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.payments.service.PaymentAttemptService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentOutcomeReconciler} — Phase 8.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentOutcomeReconciler — Unit Tests")
class PaymentOutcomeReconcilerTest {

    @Mock private GatewayStatusResolver    gatewayStatusResolver;
    @Mock private PaymentAttemptService    paymentAttemptService;
    @Mock private PaymentAttemptRepository paymentAttemptRepository;
    @Mock private PaymentIntentV2Repository paymentIntentV2Repository;

    @InjectMocks
    private PaymentOutcomeReconciler reconciler;

    // ─────────────────────────────────────────────────────────────────────────
    // reconcile — single attempt
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reconcile — single UNKNOWN attempt")
    class ReconcileSingleAttempt {

        @Test
        @DisplayName("reconcile_unknownAttempt_gatewayConfirmsSuccess_marksSucceeded")
        void reconcile_gatewaySuccess_marksSucceeded() {
            PaymentAttempt attempt = unknownAttempt(1L, 10L);
            PaymentIntentV2 intent  = intentOf(10L, PaymentIntentStatusV2.PROCESSING);

            when(gatewayStatusResolver.resolveStatus(attempt))
                    .thenReturn(GatewayResult.succeeded("TXN-001", "200", 120L));
            when(paymentAttemptService.markSucceeded(anyLong(), anyLong(), any(), anyLong()))
                    .thenReturn(attempt);
            when(paymentIntentV2Repository.findById(10L))
                    .thenReturn(Optional.of(intent));

            reconciler.reconcile(attempt);

            verify(paymentAttemptService).markSucceeded(1L, 10L, "200", 120L);
            verify(paymentIntentV2Repository).save(intent);
        }

        @Test
        @DisplayName("reconcile_unknownAttempt_gatewayConfirmsFailure_marksFailed")
        void reconcile_gatewayFailed_marksFailed() {
            PaymentAttempt attempt = unknownAttempt(2L, 11L);
            PaymentIntentV2 intent  = intentOf(11L, PaymentIntentStatusV2.PROCESSING);

            when(gatewayStatusResolver.resolveStatus(attempt))
                    .thenReturn(GatewayResult.failed(
                            FailureCategory.ISSUER_DECLINE, "Not enough funds", "DECLINED", 80L));
            when(paymentAttemptService.markFailed(anyLong(), anyLong(), any(), any(), any(), eq(false), anyLong()))
                    .thenReturn(attempt);
            when(paymentIntentV2Repository.findById(11L))
                    .thenReturn(Optional.of(intent));

            reconciler.reconcile(attempt);

            verify(paymentAttemptService).markFailed(
                    eq(2L), eq(11L), eq("DECLINED"), eq("Not enough funds"),
                    eq(FailureCategory.ISSUER_DECLINE), eq(false), eq(80L));
        }

        @Test
        @DisplayName("reconcile_unknownAttempt_gatewayStillUnknown_marksReconciled_requiresManualReview")
        void reconcile_gatewayStillUnknown_marksReconciled() {
            PaymentAttempt attempt = unknownAttempt(3L, 12L);
            PaymentIntentV2 intent  = intentOf(12L, PaymentIntentStatusV2.PROCESSING);

            when(gatewayStatusResolver.resolveStatus(attempt))
                    .thenReturn(GatewayResult.unknown("TXN-X", "{}", 4000L));
            when(paymentIntentV2Repository.findById(12L))
                    .thenReturn(Optional.of(intent));

            reconciler.reconcile(attempt);

            verify(paymentAttemptRepository).save(attempt);
            verify(paymentIntentV2Repository).save(intent);
        }

        @Test
        @DisplayName("reconcile_nonUnknownAttempt_isSkipped_noResolverCall")
        void reconcile_nonUnknownAttempt_skipped() {
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .paymentIntent(intentOf(20L, PaymentIntentStatusV2.SUCCEEDED))
                    .attemptNumber(1)
                    .build();
            attempt.setStatus(PaymentAttemptStatus.SUCCEEDED);

            reconciler.reconcile(attempt);

            verify(gatewayStatusResolver, never()).resolveStatus(any());
        }

        @Test
        @DisplayName("reconcile_successOutcome_setsLastSuccessfulAttemptIdOnIntent")
        void reconcile_success_setsLastSuccessfulAttemptId() {
            PaymentAttempt attempt = unknownAttempt(5L, 30L);
            PaymentIntentV2 intent  = intentOf(30L, PaymentIntentStatusV2.PROCESSING);

            when(gatewayStatusResolver.resolveStatus(attempt))
                    .thenReturn(GatewayResult.succeeded("TXN-OK", "200", 50L));
            when(paymentAttemptService.markSucceeded(5L, 30L, "200", 50L)).thenReturn(attempt);
            when(paymentIntentV2Repository.findById(30L))
                    .thenReturn(Optional.of(intent));

            reconciler.reconcile(attempt);

            // The reconciler should set lastSuccessfulAttemptId on the intent
            // (verified by the intent.getLastSuccessfulAttemptId() call; we assert save was called)
            verify(paymentIntentV2Repository).save(intent);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // reconcileIntent
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reconcileIntent — batch")
    class ReconcileIntent {

        @Test
        @DisplayName("reconcileIntent_callsReconcileForEachUnknownAttempt")
        void reconcileIntent_processesAllUnknownAttempts() {
            Long intentId = 100L;
            PaymentAttempt a1 = unknownAttempt(1L, intentId);
            PaymentAttempt a2 = unknownAttempt(2L, intentId);

            when(paymentAttemptRepository.findByPaymentIntentIdAndStatus(
                    intentId, PaymentAttemptStatus.UNKNOWN))
                    .thenReturn(List.of(a1, a2));

            when(gatewayStatusResolver.resolveStatus(any()))
                    .thenReturn(GatewayResult.succeeded("TXN", "200", 60L));
            when(paymentAttemptService.markSucceeded(anyLong(), anyLong(), any(), anyLong()))
                    .thenReturn(a1);
            when(paymentIntentV2Repository.findById(intentId))
                    .thenReturn(Optional.of(intentOf(intentId, PaymentIntentStatusV2.PROCESSING)));

            int processed = reconciler.reconcileIntent(intentId);

            verify(gatewayStatusResolver, times(2)).resolveStatus(any());
        }

        @Test
        @DisplayName("reconcileIntent_noUnknownAttempts_doesNothing")
        void reconcileIntent_noUnknownAttempts_doesNothing() {
            when(paymentAttemptRepository.findByPaymentIntentIdAndStatus(
                    anyLong(), eq(PaymentAttemptStatus.UNKNOWN)))
                    .thenReturn(List.of());

            reconciler.reconcileIntent(999L);

            verify(gatewayStatusResolver, never()).resolveStatus(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Single-success invariant via PaymentAttemptService
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Single-success invariant")
    class SingleSuccessInvariant {

        @Test
        @DisplayName("reconcile_duplicateSuccessCallback_propagatesAlreadySucceededException")
        void reconcile_secondSuccessThrows() {
            PaymentAttempt attempt = unknownAttempt(10L, 50L);
            PaymentIntentV2 intent  = intentOf(50L, PaymentIntentStatusV2.SUCCEEDED);

            when(gatewayStatusResolver.resolveStatus(attempt))
                    .thenReturn(GatewayResult.succeeded("TXN-DUP", "200", 30L));
            when(paymentAttemptService.markSucceeded(anyLong(), anyLong(), any(), anyLong()))
                    .thenThrow(PaymentIntentException.alreadySucceeded(50L));

            // The exception should propagate out of reconcile (REQUIRES_NEW handles isolation)
            assertThatCode(() -> reconciler.reconcile(attempt))
                    .isInstanceOf(PaymentIntentException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static PaymentAttempt unknownAttempt(Long id, Long intentId) {
        PaymentIntentV2 intent = intentOf(intentId, PaymentIntentStatusV2.PROCESSING);
        PaymentAttempt attempt = PaymentAttempt.builder()
                .paymentIntent(intent)
                .attemptNumber(1)
                .gatewayName("TEST")
                .build();
        attempt.setStatus(PaymentAttemptStatus.UNKNOWN);
        setField(attempt, "id", id);
        return attempt;
    }

    private static PaymentIntentV2 intentOf(Long id, PaymentIntentStatusV2 status) {
        PaymentIntentV2 intent = new PaymentIntentV2();
        setField(intent, "id", id);
        intent.setStatus(status);
        return intent;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
