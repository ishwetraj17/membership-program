package com.firstclub.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.payments.dto.GatewayOtpConfirmRequest;
import com.firstclub.payments.dto.GatewayPayRequest;
import com.firstclub.payments.dto.PaymentIntentDTO;
import com.firstclub.payments.dto.WebhookPayloadDTO;
import com.firstclub.payments.entity.WebhookEvent;
import com.firstclub.payments.model.PaymentIntentStatus;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.payments.repository.WebhookEventRepository;
import com.firstclub.payments.service.PaymentIntentService;
import com.firstclub.payments.service.WebhookSignatureService;
import com.firstclub.membership.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for the Payments module.
 *
 * <p>Tests run against a live PostgreSQL container (via Testcontainers) and are
 * skipped automatically when Docker is unavailable.
 *
 * <h3>test 1 — full happy-path flow</h3>
 * Creates a PaymentIntent → POST /gateway/pay with outcome SUCCEEDED →
 * polls until the async webhook fires → asserts PI=SUCCEEDED + Payment=CAPTURED.
 *
 * <h3>test 2 — 3-DS / OTP flow</h3>
 * Creates a PI → POST /gateway/pay REQUIRES_ACTION → POST /gateway/otp/confirm
 * → polls until SUCCEEDED.
 *
 * <h3>test 3 — duplicate webhook idempotency</h3>
 * Sends the same signed webhook event twice to /api/v1/webhooks/gateway and
 * asserts that only one Payment row is created.
 */
class PaymentIntegrationTest extends PostgresIntegrationTestBase {

    @Autowired private PaymentIntentService paymentIntentService;
    @Autowired private PaymentRepository    paymentRepository;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private WebhookSignatureService signatureService;
    @Autowired private ObjectMapper          objectMapper;
    @Autowired private TestRestTemplate      restTemplate;

    // -------------------------------------------------------------------------
    // Test 1 — full flow (SUCCEEDED)
    // -------------------------------------------------------------------------

    @Test
    void fullPaymentFlow_gatewaySucceeded_createsPaymentAndSetsSucceeded() throws Exception {
        // 1. Create a PaymentIntent
        PaymentIntentDTO pi = paymentIntentService.createForInvoice(null, new BigDecimal("999.00"), "INR");
        assertThat(pi.getStatus()).isEqualTo(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);

        // 2. Simulate a gateway charge → the gateway schedules an async webhook in 2-5s
        ResponseEntity<?> payResponse = restTemplate.postForEntity(
                "/gateway/pay",
                new GatewayPayRequest(pi.getId(), "SUCCEEDED"),
                Map.class);
        assertThat(payResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 3. Poll until the PI transitions to SUCCEEDED (or timeout after 15s)
        boolean succeeded = pollUntil(
                () -> paymentIntentService.findById(pi.getId()).getStatus() == PaymentIntentStatus.SUCCEEDED,
                15, TimeUnit.SECONDS);
        assertThat(succeeded).as("PaymentIntent should reach SUCCEEDED within 15s").isTrue();

        // 4. A CAPTURED Payment row must exist
        assertThat(paymentRepository.findByPaymentIntentId(pi.getId()))
                .isNotEmpty()
                .allMatch(p -> p.getStatus().name().equals("CAPTURED"));
    }

    // -------------------------------------------------------------------------
    // Test 2 — 3-DS / OTP flow
    // -------------------------------------------------------------------------

    @Test
    void otpFlow_requiresAction_thenConfirm_thenSucceeded() throws Exception {
        PaymentIntentDTO pi = paymentIntentService.createForInvoice(null, new BigDecimal("499.00"), "INR");

        // Step 1: gateway returns REQUIRES_ACTION (no immediate webhook)
        ResponseEntity<?> payResp = restTemplate.postForEntity(
                "/gateway/pay",
                new GatewayPayRequest(pi.getId(), "REQUIRES_ACTION"),
                Map.class);
        assertThat(payResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(paymentIntentService.findById(pi.getId()).getStatus())
                .isEqualTo(PaymentIntentStatus.REQUIRES_ACTION);

        // Step 2: customer completes OTP → schedules SUCCEEDED webhook
        ResponseEntity<?> otpResp = restTemplate.postForEntity(
                "/gateway/otp/confirm",
                new GatewayOtpConfirmRequest(pi.getId()),
                Map.class);
        assertThat(otpResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // Step 3: poll for SUCCEEDED
        boolean succeeded = pollUntil(
                () -> paymentIntentService.findById(pi.getId()).getStatus() == PaymentIntentStatus.SUCCEEDED,
                15, TimeUnit.SECONDS);
        assertThat(succeeded).as("PaymentIntent should reach SUCCEEDED after OTP confirm").isTrue();
    }

    // -------------------------------------------------------------------------
    // Test 3 — duplicate webhook idempotency
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void duplicateWebhookEvent_doesNotCreateDuplicatePayment() throws Exception {
        // Arrange: create a PI and transition it to PROCESSING so SUCCEEDED is valid
        PaymentIntentDTO pi = paymentIntentService.createForInvoice(null, new BigDecimal("199.00"), "INR");
        paymentIntentService.markProcessing(pi.getId());

        // Build a deterministic webhook payload (fixed eventId)
        String fixedEventId    = "evt_dup_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String fixedGatewayTxn = "gwtxn_dup_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        WebhookPayloadDTO payload = WebhookPayloadDTO.builder()
                .eventId(fixedEventId)
                .eventType("PAYMENT_INTENT.SUCCEEDED")
                .paymentIntentId(pi.getId())
                .amount(new BigDecimal("199.00"))
                .currency("INR")
                .gatewayTxnId(fixedGatewayTxn)
                .timestamp(LocalDateTime.now())
                .build();

        String payloadJson = objectMapper.writeValueAsString(payload);
        String signature   = signatureService.sign(payloadJson);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Signature", signature);

        // Act: send the same event twice
        ResponseEntity<Map<String, Object>> first  = restTemplate.exchange(
                "/api/v1/webhooks/gateway", HttpMethod.POST, new HttpEntity<>(payloadJson, headers), new ParameterizedTypeReference<Map<String, Object>>() {});
        ResponseEntity<Map<String, Object>> second = restTemplate.exchange(
                "/api/v1/webhooks/gateway", HttpMethod.POST, new HttpEntity<>(payloadJson, headers), new ParameterizedTypeReference<Map<String, Object>>() {});

        // Assert: both calls succeed from the HTTP perspective
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).containsEntry("result", "DUPLICATE");

        // Assert: only ONE Payment row for this PI
        assertThat(paymentRepository.findByPaymentIntentId(pi.getId())).hasSize(1);

        // Assert: only ONE webhook_event row for this eventId
        Optional<WebhookEvent> storedEvent = webhookEventRepository.findByEventId(fixedEventId);
        assertThat(storedEvent).isPresent();
        assertThat(storedEvent.get().isProcessed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private boolean pollUntil(BooleanSupplier condition, int timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }
}
