package com.firstclub.payments.service;

import com.firstclub.payments.dto.PaymentAttemptResponseDTO;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Mutation-killing tests for {@link PaymentAttemptServiceImpl}.
 *
 * <p>Targets surviving and no-coverage mutants identified by PIT baseline:
 * <ul>
 *   <li>markAuthorized — 4 no-coverage mutants</li>
 *   <li>markSucceeded — 10 no-coverage mutants (incl. exactly-one-SUCCEEDED invariant)</li>
 *   <li>markUnknown — 4 no-coverage mutants</li>
 *   <li>markReconciled — 4 no-coverage mutants</li>
 *   <li>markRequiresAction — 3 no-coverage mutants</li>
 *   <li>markFailed — 3 survived mutants (guardNotTerminal, responseCode, latencyMs)</li>
 *   <li>listByPaymentIntent — 2 no-coverage mutants</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentAttemptServiceImpl — Mutation-killing tests")
class PaymentAttemptServiceMutationTest {

    @Mock private PaymentAttemptRepository paymentAttemptRepository;
    @Mock private PaymentIntentV2Repository paymentIntentV2Repository;
    @Mock private PaymentAttemptMapper paymentAttemptMapper;

    @InjectMocks
    private PaymentAttemptServiceImpl service;

    private static final Long MERCHANT_ID = 10L;
    private static final Long INTENT_ID   = 100L;
    private static final Long ATTEMPT_ID  = 1L;

    /**
     * Builds a non-terminal attempt in STARTED state, ready for transition.
     */
    private PaymentAttempt startedAttempt() {
        return PaymentAttempt.builder()
                .id(ATTEMPT_ID)
                .attemptNumber(1)
                .gatewayName("razorpay")
                .status(PaymentAttemptStatus.STARTED)
                .build();
    }

    /**
     * Stubs loadAttempt to return the given attempt.
     */
    private void stubLoad(PaymentAttempt attempt) {
        when(paymentAttemptRepository.findByIdAndPaymentIntentId(ATTEMPT_ID, INTENT_ID))
                .thenReturn(Optional.of(attempt));
    }

    /**
     * Stubs save to return the argument (pass-through).
     */
    private void stubSavePassThrough() {
        when(paymentAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── markAuthorized — 4 no-coverage mutants ────────────────────────────────

    @Nested
    @DisplayName("markAuthorized")
    class MarkAuthorizedTests {

        @Test
        @DisplayName("transitions STARTED → AUTHORIZED and sets gatewayReference")
        void transitionsToAuthorized() {
            PaymentAttempt attempt = startedAttempt();
            stubLoad(attempt);
            stubSavePassThrough();

            PaymentAttempt result = service.markAuthorized(ATTEMPT_ID, INTENT_ID, "gw-ref-123");

            // Kills: setStatus removed, return null
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(PaymentAttemptStatus.AUTHORIZED);
            // Kills: setGatewayReference removed
            assertThat(result.getGatewayReference()).isEqualTo("gw-ref-123");
        }

        @Test
        @DisplayName("rejects transition from terminal CAPTURED state")
        void alreadyTerminal_throws() {
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .id(ATTEMPT_ID).status(PaymentAttemptStatus.CAPTURED).build();
            stubLoad(attempt);

            // Kills: guardNotTerminal call removed
            assertThatThrownBy(() -> service.markAuthorized(ATTEMPT_ID, INTENT_ID, "ref"))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ATTEMPT_IMMUTABLE");
        }
    }

    // ── markSucceeded — 10 no-coverage mutants ────────────────────────────────

    @Nested
    @DisplayName("markSucceeded")
    class MarkSucceededTests {

        @Test
        @DisplayName("transitions STARTED → SUCCEEDED with all fields set")
        void transitionsToSucceeded() {
            when(paymentAttemptRepository.countByPaymentIntentIdAndStatus(
                    INTENT_ID, PaymentAttemptStatus.SUCCEEDED)).thenReturn(0);
            PaymentAttempt attempt = startedAttempt();
            stubLoad(attempt);
            stubSavePassThrough();

            PaymentAttempt result = service.markSucceeded(ATTEMPT_ID, INTENT_ID, "OK", 55L);

            // Kills: return null, setStatus removed
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            // Kills: setResponseCode removed
            assertThat(result.getResponseCode()).isEqualTo("OK");
            // Kills: setLatencyMs removed
            assertThat(result.getLatencyMs()).isEqualTo(55L);
            // Kills: setCompletedAt removed
            assertThat(result.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("throws alreadySucceeded when another attempt already SUCCEEDED (>0 check)")
        void duplicateSucceeded_throws() {
            // existingSuccess = 1 → must throw
            // Kills: conditional boundary (>0 vs >=0), removed conditional (true/false)
            when(paymentAttemptRepository.countByPaymentIntentIdAndStatus(
                    INTENT_ID, PaymentAttemptStatus.SUCCEEDED)).thenReturn(1);

            assertThatThrownBy(() -> service.markSucceeded(ATTEMPT_ID, INTENT_ID, "OK", 10L))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "PAYMENT_INTENT_ALREADY_SUCCEEDED");
        }

        @Test
        @DisplayName("allows first SUCCEEDED when count is exactly 0")
        void firstSucceeded_allowed() {
            // existingSuccess = 0 → must NOT throw
            // Kills: boundary mutant (>= 0 would wrongly throw)
            when(paymentAttemptRepository.countByPaymentIntentIdAndStatus(
                    INTENT_ID, PaymentAttemptStatus.SUCCEEDED)).thenReturn(0);
            PaymentAttempt attempt = startedAttempt();
            stubLoad(attempt);
            stubSavePassThrough();

            PaymentAttempt result = service.markSucceeded(ATTEMPT_ID, INTENT_ID, "OK", 10L);

            assertThat(result.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        }

        @Test
        @DisplayName("rejects transition from terminal state (guard)")
        void alreadyTerminal_throws() {
            when(paymentAttemptRepository.countByPaymentIntentIdAndStatus(
                    INTENT_ID, PaymentAttemptStatus.SUCCEEDED)).thenReturn(0);
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .id(ATTEMPT_ID).status(PaymentAttemptStatus.FAILED).build();
            stubLoad(attempt);

            // Kills: guardNotTerminal call removed
            assertThatThrownBy(() -> service.markSucceeded(ATTEMPT_ID, INTENT_ID, "OK", 10L))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ATTEMPT_IMMUTABLE");
        }
    }

    // ── markUnknown — 4 no-coverage mutants ───────────────────────────────────

    @Nested
    @DisplayName("markUnknown")
    class MarkUnknownTests {

        @Test
        @DisplayName("transitions STARTED → UNKNOWN; completedAt stays null")
        void transitionsToUnknown() {
            PaymentAttempt attempt = startedAttempt();
            stubLoad(attempt);
            stubSavePassThrough();

            PaymentAttempt result = service.markUnknown(ATTEMPT_ID, INTENT_ID, 9999L);

            // Kills: return null, setStatus removed
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            // Kills: setLatencyMs removed
            assertThat(result.getLatencyMs()).isEqualTo(9999L);
            // UNKNOWN is not terminal — completedAt must remain null
            assertThat(result.getCompletedAt()).isNull();
        }

        @Test
        @DisplayName("rejects transition from terminal SUCCEEDED state")
        void alreadyTerminal_throws() {
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .id(ATTEMPT_ID).status(PaymentAttemptStatus.SUCCEEDED).build();
            stubLoad(attempt);

            // Kills: guardNotTerminal call removed
            assertThatThrownBy(() -> service.markUnknown(ATTEMPT_ID, INTENT_ID, 100L))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ATTEMPT_IMMUTABLE");
        }
    }

    // ── markReconciled — 4 no-coverage mutants ────────────────────────────────

    @Nested
    @DisplayName("markReconciled")
    class MarkReconciledTests {

        @Test
        @DisplayName("transitions STARTED → RECONCILED with completedAt set")
        void transitionsToReconciled() {
            PaymentAttempt attempt = startedAttempt();
            stubLoad(attempt);
            stubSavePassThrough();

            PaymentAttempt result = service.markReconciled(ATTEMPT_ID, INTENT_ID);

            // Kills: return null, setStatus removed
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(PaymentAttemptStatus.RECONCILED);
            // Kills: setCompletedAt removed
            assertThat(result.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("rejects transition from terminal RECONCILED state")
        void alreadyTerminal_throws() {
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .id(ATTEMPT_ID).status(PaymentAttemptStatus.RECONCILED).build();
            stubLoad(attempt);

            // Kills: guardNotTerminal call removed
            assertThatThrownBy(() -> service.markReconciled(ATTEMPT_ID, INTENT_ID))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ATTEMPT_IMMUTABLE");
        }
    }

    // ── markRequiresAction — 3 no-coverage mutants ────────────────────────────

    @Nested
    @DisplayName("markRequiresAction")
    class MarkRequiresActionTests {

        @Test
        @DisplayName("transitions STARTED → REQUIRES_ACTION")
        void transitionsToRequiresAction() {
            PaymentAttempt attempt = startedAttempt();
            stubLoad(attempt);
            stubSavePassThrough();

            PaymentAttempt result = service.markRequiresAction(ATTEMPT_ID, INTENT_ID);

            // Kills: return null, setStatus removed
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(PaymentAttemptStatus.REQUIRES_ACTION);
        }

        @Test
        @DisplayName("rejects transition from terminal CANCELLED state")
        void alreadyTerminal_throws() {
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .id(ATTEMPT_ID).status(PaymentAttemptStatus.CANCELLED).build();
            stubLoad(attempt);

            // Kills: guardNotTerminal call removed
            assertThatThrownBy(() -> service.markRequiresAction(ATTEMPT_ID, INTENT_ID))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ATTEMPT_IMMUTABLE");
        }
    }

    // ── markFailed — 3 survived mutants ───────────────────────────────────────

    @Nested
    @DisplayName("markFailed — surviving mutant killers")
    class MarkFailedSurvivedMutantTests {

        @Test
        @DisplayName("terminal FAILED attempt rejects further markFailed (guard)")
        void alreadyFailed_throws() {
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .id(ATTEMPT_ID).status(PaymentAttemptStatus.FAILED).build();
            stubLoad(attempt);

            // Kills survived mutant: guardNotTerminal call removed on line 72
            assertThatThrownBy(() -> service.markFailed(
                    ATTEMPT_ID, INTENT_ID, "ERR", "msg",
                    FailureCategory.ISSUER_DECLINE, false, 10L))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ATTEMPT_IMMUTABLE");
        }

        @Test
        @DisplayName("responseCode and latencyMs are set correctly")
        void setsResponseCodeAndLatencyMs() {
            PaymentAttempt attempt = startedAttempt();
            stubLoad(attempt);
            stubSavePassThrough();

            PaymentAttempt result = service.markFailed(
                    ATTEMPT_ID, INTENT_ID, "DECLINED", "Insufficient funds",
                    FailureCategory.ISSUER_DECLINE, false, 42L);

            // Kills survived mutant: setResponseCode removed on line 74
            assertThat(result.getResponseCode()).isEqualTo("DECLINED");
            // Kills survived mutant: setLatencyMs removed on line 78
            assertThat(result.getLatencyMs()).isEqualTo(42L);
        }
    }

    // ── listByPaymentIntent — 2 no-coverage mutants ──────────────────────────

    @Nested
    @DisplayName("listByPaymentIntent")
    class ListByPaymentIntentTests {

        @Test
        @DisplayName("returns mapped DTOs for valid merchant+intent")
        void returnsMappedAttempts() {
            PaymentIntentV2 intent = PaymentIntentV2.builder()
                    .id(INTENT_ID).build();
            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, INTENT_ID))
                    .thenReturn(Optional.of(intent));

            PaymentAttempt attempt = startedAttempt();
            when(paymentAttemptRepository.findByPaymentIntentIdOrderByAttemptNumberAsc(INTENT_ID))
                    .thenReturn(List.of(attempt));

            PaymentAttemptResponseDTO dto = PaymentAttemptResponseDTO.builder()
                    .id(ATTEMPT_ID).paymentIntentId(INTENT_ID).build();
            when(paymentAttemptMapper.toResponseDTO(attempt)).thenReturn(dto);

            List<PaymentAttemptResponseDTO> result =
                    service.listByPaymentIntent(MERCHANT_ID, INTENT_ID);

            // Kills: return empty list mutant
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo(ATTEMPT_ID);
        }

        @Test
        @DisplayName("throws when intent does not belong to merchant")
        void wrongMerchant_throws() {
            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, INTENT_ID))
                    .thenReturn(Optional.empty());

            // Kills: lambda return null mutant (exception factory returns non-null)
            assertThatThrownBy(() -> service.listByPaymentIntent(MERCHANT_ID, INTENT_ID))
                    .isInstanceOf(PaymentIntentException.class);
        }
    }
}
