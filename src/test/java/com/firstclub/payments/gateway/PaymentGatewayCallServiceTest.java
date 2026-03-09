package com.firstclub.payments.gateway;

import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentIntentV2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;

/**
 * Unit tests for {@link PaymentGatewayCallService} — Phase 8.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentGatewayCallService — Unit Tests")
class PaymentGatewayCallServiceTest {

    private PaymentGatewayCallService service;

    @BeforeEach
    void setUp() {
        // Use a spy so we can verify protected method interactions if needed
        service = new PaymentGatewayCallService();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildGatewayIdempotencyKey
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildGatewayIdempotencyKey")
    class BuildGatewayIdempotencyKey {

        @Test
        @DisplayName("buildGatewayIdempotencyKey_format_is_firstclub_colon_intentId_colon_attemptNumber")
        void buildGatewayIdempotencyKey_correctFormat() {
            String key = service.buildGatewayIdempotencyKey(42L, 3);

            assertThat(key).isEqualTo("firstclub:42:3");
        }

        @Test
        @DisplayName("buildGatewayIdempotencyKey_deterministic_sameMath_sameKey")
        void buildGatewayIdempotencyKey_isDeterministic() {
            String first  = service.buildGatewayIdempotencyKey(100L, 1);
            String second = service.buildGatewayIdempotencyKey(100L, 1);

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("buildGatewayIdempotencyKey_differentAttemptNumbers_differentKeys")
        void buildGatewayIdempotencyKey_differentAttempts_differentKeys() {
            String attempt1 = service.buildGatewayIdempotencyKey(7L, 1);
            String attempt2 = service.buildGatewayIdempotencyKey(7L, 2);

            assertThat(attempt1).isNotEqualTo(attempt2);
        }

        @Test
        @DisplayName("buildGatewayIdempotencyKey_differentIntentIds_differentKeys")
        void buildGatewayIdempotencyKey_differentIntents_differentKeys() {
            String intent1 = service.buildGatewayIdempotencyKey(1L, 1);
            String intent2 = service.buildGatewayIdempotencyKey(2L, 1);

            assertThat(intent1).isNotEqualTo(intent2);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // submitPayment — attempt stamping
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("submitPayment — attempt stamping")
    class SubmitPayment {

        @Test
        @DisplayName("submitPayment_stampsAttemptWithIdempotencyKey")
        void submitPayment_stampsIdempotencyKey() {
            PaymentAttempt attempt = attemptOf(10L, 1);
            PaymentIntentV2 intent = intentOf(10L);

            service.submitPayment(attempt, intent);

            assertThat(attempt.getGatewayIdempotencyKey()).isEqualTo("firstclub:10:1");
        }

        @Test
        @DisplayName("submitPayment_stampsAttemptWithStartedAt")
        void submitPayment_stampsStartedAt() {
            PaymentAttempt attempt = attemptOf(11L, 2);
            PaymentIntentV2 intent = intentOf(11L);

            service.submitPayment(attempt, intent);

            assertThat(attempt.getStartedAt()).isNotNull();
        }

        @Test
        @DisplayName("submitPayment_stampsAttemptWithProcessorNodeId")
        void submitPayment_stampsProcessorNodeId() {
            PaymentAttempt attempt = attemptOf(12L, 1);
            PaymentIntentV2 intent = intentOf(12L);

            service.submitPayment(attempt, intent);

            assertThat(attempt.getProcessorNodeId()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("submitPayment_defaultSimulation_returnsSucceeded")
        void submitPayment_defaultSimulation_returnsSucceeded() {
            PaymentAttempt attempt = attemptOf(20L, 1);
            PaymentIntentV2 intent = intentOf(20L);

            GatewayResult result = service.submitPayment(attempt, intent);

            assertThat(result.isSucceeded()).isTrue();
        }

        @Test
        @DisplayName("submitPayment_returnedResult_hasNonNullLatency")
        void submitPayment_result_hasLatency() {
            PaymentAttempt attempt = attemptOf(30L, 1);
            PaymentIntentV2 intent = intentOf(30L);

            GatewayResult result = service.submitPayment(attempt, intent);

            assertThat(result.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GatewayResult factories
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GatewayResult factories")
    class GatewayResultFactories {

        @Test
        @DisplayName("GatewayResult.succeeded_status_isSucceeded")
        void succeeded_status() {
            GatewayResult r = GatewayResult.succeeded("TXN-001", "200", 80L);
            assertThat(r.isSucceeded()).isTrue();
            assertThat(r.isFailed()).isFalse();
            assertThat(r.needsReconciliation()).isFalse();
        }

        @Test
        @DisplayName("GatewayResult.failed_status_isFailed")
        void failed_status() {
            GatewayResult r = GatewayResult.failed(
                    com.firstclub.payments.entity.FailureCategory.ISSUER_DECLINE,
                    "Declined", "DECLINED", 50L);
            assertThat(r.isFailed()).isTrue();
            assertThat(r.isSucceeded()).isFalse();
            assertThat(r.needsReconciliation()).isFalse();
        }

        @Test
        @DisplayName("GatewayResult.timeout_needsReconciliation_isTrue")
        void timeout_needsReconciliation() {
            GatewayResult r = GatewayResult.timeout(5000L);
            assertThat(r.needsReconciliation()).isTrue();
            assertThat(r.isSucceeded()).isFalse();
        }

        @Test
        @DisplayName("GatewayResult.unknown_needsReconciliation_isTrue")
        void unknown_needsReconciliation() {
            GatewayResult r = GatewayResult.unknown("TXN-X", "{}", 3000L);
            assertThat(r.needsReconciliation()).isTrue();
            assertThat(r.isFailed()).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static PaymentAttempt attemptOf(Long intentId, int attemptNumber) {
        PaymentIntentV2 intent = intentOf(intentId);
        return PaymentAttempt.builder()
                .paymentIntent(intent)
                .attemptNumber(attemptNumber)
                .gatewayName("TEST_GATEWAY")
                .build();
    }

    private static PaymentIntentV2 intentOf(Long id) {
        PaymentIntentV2 intent = new PaymentIntentV2();
        // Set the id via reflection since it is auto-generated
        try {
            var field = PaymentIntentV2.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(intent, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return intent;
    }
}
