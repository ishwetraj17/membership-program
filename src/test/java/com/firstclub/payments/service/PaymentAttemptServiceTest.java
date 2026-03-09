package com.firstclub.payments.service;

import com.firstclub.payments.entity.FailureCategory;
import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentAttemptStatus;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.exception.PaymentIntentException;
import com.firstclub.payments.mapper.PaymentAttemptMapper;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.payments.service.impl.PaymentAttemptServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentAttemptServiceImpl Unit Tests")
class PaymentAttemptServiceTest {

    @Mock private PaymentAttemptRepository paymentAttemptRepository;
    @Mock private PaymentIntentV2Repository paymentIntentV2Repository;
    @Mock private PaymentAttemptMapper paymentAttemptMapper;

    @InjectMocks
    private PaymentAttemptServiceImpl service;

    private static final Long INTENT_ID  = 100L;
    private static final Long ATTEMPT_ID = 1L;

    // ── createAttempt ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createAttempt")
    class CreateAttemptTests {

        @Test
        @DisplayName("saves attempt with STARTED status")
        void savesWithStartedStatus() {
            PaymentIntentV2 intent = PaymentIntentV2.builder().id(INTENT_ID).build();
            PaymentAttempt saved = PaymentAttempt.builder()
                    .id(ATTEMPT_ID).paymentIntent(intent)
                    .attemptNumber(1).gatewayName("razorpay")
                    .status(PaymentAttemptStatus.STARTED).build();
            when(paymentAttemptRepository.save(any())).thenReturn(saved);

            PaymentAttempt result = service.createAttempt(intent, 1, "razorpay");

            assertThat(result.getStatus()).isEqualTo(PaymentAttemptStatus.STARTED);
            assertThat(result.getAttemptNumber()).isEqualTo(1);
            verify(paymentAttemptRepository).save(argThat(a ->
                    a.getGatewayName().equals("razorpay")
                    && a.getAttemptNumber() == 1
                    && a.getStatus() == PaymentAttemptStatus.STARTED));
        }
    }

    // ── markCaptured ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("markCaptured")
    class MarkCapturedTests {

        @Test
        @DisplayName("transitions STARTED -> CAPTURED and sets completedAt")
        void transitionsToCaptured() {
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .id(ATTEMPT_ID).attemptNumber(1).gatewayName("razorpay")
                    .status(PaymentAttemptStatus.STARTED).build();
            when(paymentAttemptRepository.findByIdAndPaymentIntentId(ATTEMPT_ID, INTENT_ID))
                    .thenReturn(Optional.of(attempt));
            when(paymentAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PaymentAttempt result = service.markCaptured(ATTEMPT_ID, INTENT_ID, "SUCCESS", 42L);

            assertThat(result.getStatus()).isEqualTo(PaymentAttemptStatus.CAPTURED);
            assertThat(result.getResponseCode()).isEqualTo("SUCCESS");
            assertThat(result.getLatencyMs()).isEqualTo(42L);
            assertThat(result.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("throws ATTEMPT_IMMUTABLE when attempt is already terminal")
        void alreadyTerminal_throws() {
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .id(ATTEMPT_ID).attemptNumber(1)
                    .status(PaymentAttemptStatus.CAPTURED).build(); // already terminal
            when(paymentAttemptRepository.findByIdAndPaymentIntentId(ATTEMPT_ID, INTENT_ID))
                    .thenReturn(Optional.of(attempt));

            assertThatThrownBy(() -> service.markCaptured(ATTEMPT_ID, INTENT_ID, "SUCCESS", 10L))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ATTEMPT_IMMUTABLE");
        }
    }

    // ── markFailed ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("markFailed")
    class MarkFailedTests {

        @Test
        @DisplayName("transitions STARTED -> FAILED with failure details")
        void transitionsToFailed() {
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .id(ATTEMPT_ID).attemptNumber(1).gatewayName("razorpay")
                    .status(PaymentAttemptStatus.STARTED).build();
            when(paymentAttemptRepository.findByIdAndPaymentIntentId(ATTEMPT_ID, INTENT_ID))
                    .thenReturn(Optional.of(attempt));
            when(paymentAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PaymentAttempt result = service.markFailed(
                    ATTEMPT_ID, INTENT_ID, "DECLINED", "Insufficient funds",
                    FailureCategory.ISSUER_DECLINE, false, 35L);

            assertThat(result.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
            assertThat(result.getFailureCategory()).isEqualTo(FailureCategory.ISSUER_DECLINE);
            assertThat(result.isRetriable()).isFalse();
            assertThat(result.getResponseMessage()).isEqualTo("Insufficient funds");
            assertThat(result.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("retriable NETWORK failure -> retriable=true")
        void networkFailure_isRetriable() {
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .id(ATTEMPT_ID).attemptNumber(1).status(PaymentAttemptStatus.STARTED).build();
            when(paymentAttemptRepository.findByIdAndPaymentIntentId(ATTEMPT_ID, INTENT_ID))
                    .thenReturn(Optional.of(attempt));
            when(paymentAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PaymentAttempt result = service.markFailed(
                    ATTEMPT_ID, INTENT_ID, "TIMEOUT", "Network timeout",
                    FailureCategory.NETWORK, true, 5000L);

            assertThat(result.isRetriable()).isTrue();
            assertThat(result.getFailureCategory()).isEqualTo(FailureCategory.NETWORK);
        }
    }

    // ── computeNextAttemptNumber ──────────────────────────────────────────────

    @Nested
    @DisplayName("computeNextAttemptNumber")
    class ComputeNextAttemptNumberTests {

        @Test
        @DisplayName("returns count + 1")
        void countPlusOne() {
            when(paymentAttemptRepository.findMaxAttemptNumberByPaymentIntentId(INTENT_ID)).thenReturn(2);

            int result = service.computeNextAttemptNumber(INTENT_ID);

            assertThat(result).isEqualTo(3);
        }

        @Test
        @DisplayName("first attempt -> returns 1")
        void noAttempts_returnsOne() {
            when(paymentAttemptRepository.findMaxAttemptNumberByPaymentIntentId(INTENT_ID)).thenReturn(0);

            int result = service.computeNextAttemptNumber(INTENT_ID);

            assertThat(result).isEqualTo(1);
        }
    }
}
