package com.firstclub.payments.recovery;

import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentAttemptStatus;
import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.gateway.GatewayResult;
import com.firstclub.payments.gateway.GatewayStatusResolver;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.payments.service.PaymentAttemptService;
import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GatewayTimeoutRecoveryScheduler} — Phase 8.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayTimeoutRecoveryScheduler — Unit Tests")
class GatewayTimeoutRecoverySchedulerTest {

    @Mock private PaymentAttemptRepository    paymentAttemptRepository;
    @Mock private PaymentOutcomeReconciler    paymentOutcomeReconciler;
    @Mock private SchedulerLockService        schedulerLockService;
    @Mock private PrimaryOnlySchedulerGuard   primaryOnlySchedulerGuard;

    @InjectMocks
    private GatewayTimeoutRecoveryScheduler scheduler;

    // ─────────────────────────────────────────────────────────────────────────
    // Guard conditions
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Guard conditions")
    class GuardConditions {

        @Test
        @DisplayName("recoverTimeoutAttempts_notPrimaryNode_skipsExecution")
        void recoverTimeoutAttempts_notPrimary_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(false);

            scheduler.recoverTimeoutAttempts();

            verify(schedulerLockService, never()).tryAcquireForBatch(any());
            verify(paymentAttemptRepository, never())
                    .findByStatusAndStartedAtBefore(any(), any());
        }

        @Test
        @DisplayName("recoverTimeoutAttempts_cannotAcquireLock_skipsExecution")
        void recoverTimeoutAttempts_lockNotAcquired_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(false);

            scheduler.recoverTimeoutAttempts();

            verify(paymentAttemptRepository, never())
                    .findByStatusAndStartedAtBefore(any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main logic
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Main batch logic")
    class BatchLogic {

        @Test
        @DisplayName("recoverTimeoutAttempts_noStaleUnknownAttempts_doesNothing")
        void recoverTimeoutAttempts_noStaleAttempts_doesNothing() {
            allowExecution();
            when(paymentAttemptRepository.findByStatusAndStartedAtBefore(
                    eq(PaymentAttemptStatus.UNKNOWN), any()))
                    .thenReturn(List.of());

            scheduler.recoverTimeoutAttempts();

            verify(paymentOutcomeReconciler, never()).reconcile(any());
        }

        @Test
        @DisplayName("recoverTimeoutAttempts_hasStaleUnknownAttempts_callsReconcilerForEach")
        void recoverTimeoutAttempts_hasStaleAttempts_reconciles() {
            PaymentAttempt a1 = staleUnknownAttempt(1L);
            PaymentAttempt a2 = staleUnknownAttempt(2L);

            allowExecution();
            when(paymentAttemptRepository.findByStatusAndStartedAtBefore(
                    eq(PaymentAttemptStatus.UNKNOWN), any()))
                    .thenReturn(List.of(a1, a2));

            scheduler.recoverTimeoutAttempts();

            verify(paymentOutcomeReconciler, times(2)).reconcile(any(PaymentAttempt.class));
        }

        @Test
        @DisplayName("recoverTimeoutAttempts_reconcileThrows_continuesBatch")
        void recoverTimeoutAttempts_oneAttemptFails_batchContinues() {
            PaymentAttempt a1 = staleUnknownAttempt(1L);
            PaymentAttempt a2 = staleUnknownAttempt(2L);

            allowExecution();
            when(paymentAttemptRepository.findByStatusAndStartedAtBefore(
                    eq(PaymentAttemptStatus.UNKNOWN), any()))
                    .thenReturn(List.of(a1, a2));

            // First reconcile throws; second should still continue
            org.mockito.Mockito.doThrow(new RuntimeException("gateway down"))
                    .doNothing()
                    .when(paymentOutcomeReconciler).reconcile(any());

            scheduler.recoverTimeoutAttempts();

            // Both attempts were attempted despite the exception on the first
            verify(paymentOutcomeReconciler, times(2)).reconcile(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void allowExecution() {
        when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
        when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);
    }

    private static PaymentAttempt staleUnknownAttempt(Long id) {
        PaymentIntentV2 intent = new PaymentIntentV2();
        setField(intent, "id", id * 100L);

        PaymentAttempt attempt = PaymentAttempt.builder()
                .paymentIntent(intent)
                .attemptNumber(1)
                .gatewayName("TEST")
                .build();
        attempt.setStatus(PaymentAttemptStatus.UNKNOWN);
        setField(attempt, "id", id);
        return attempt;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
