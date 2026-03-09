package com.firstclub.payments;

import com.firstclub.customer.dto.CustomerCreateRequestDTO;
import com.firstclub.customer.dto.CustomerResponseDTO;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.merchant.dto.MerchantCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.merchant.dto.MerchantStatusUpdateRequestDTO;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.service.MerchantService;
import com.firstclub.payments.dto.PaymentAttemptResponseDTO;
import com.firstclub.payments.dto.PaymentIntentConfirmRequestDTO;
import com.firstclub.payments.dto.PaymentIntentCreateRequestDTO;
import com.firstclub.payments.dto.PaymentIntentV2ResponseDTO;
import com.firstclub.payments.dto.PaymentMethodCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodResponseDTO;
import com.firstclub.payments.entity.CaptureMode;
import com.firstclub.payments.entity.PaymentAttemptStatus;
import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.entity.PaymentMethodType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for PaymentIntentV2Controller.
 * Runs against Testcontainers Postgres; skipped without Docker.
 */
@DisplayName("PaymentIntentV2Controller - Integration Tests")
class PaymentIntentV2ControllerTest extends com.firstclub.membership.PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private MerchantService merchantService;

    private String adminToken;
    private Long merchantId;
    private Long customerId;
    private Long paymentMethodId;
    private Long merchant2Id;
    private Long customer2Id;

    // ── Setup ─────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        // Auth
        ResponseEntity<JwtResponseDTO> auth = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new HttpEntity<>(LoginRequestDTO.builder()
                        .email(ADMIN_EMAIL).password(ADMIN_PASSWORD).build(), jsonHeaders()),
                JwtResponseDTO.class);
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminToken = auth.getBody().getToken();

        // Merchant 1
        String ts = String.valueOf(System.nanoTime());
        MerchantResponseDTO m1 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder()
                        .merchantCode("PIV2_M1_" + ts).legalName("PI V2 Tenant 1")
                        .displayName("PIV2-1").supportEmail("piv2m1_" + ts + "@test.com")
                        .defaultCurrency("INR").countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m1.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchantId = m1.getId();

        // Customer under merchant 1
        CustomerResponseDTO c1 = rest.exchange(
                base() + "/api/v2/merchants/" + merchantId + "/customers",
                HttpMethod.POST,
                new HttpEntity<>(CustomerCreateRequestDTO.builder()
                        .fullName("PI V2 Customer 1")
                        .email("piv2_cust1_" + ts + "@test.com")
                        .phone("+91-9111111111")
                        .billingAddress("1 PI Street").build(), authHeaders()),
                CustomerResponseDTO.class).getBody();
        assertThat(c1).isNotNull();
        customerId = c1.getId();

        // Payment method for customer 1
        PaymentMethodResponseDTO pm = rest.exchange(
                base() + "/api/v2/merchants/" + merchantId + "/customers/" + customerId
                        + "/payment-methods",
                HttpMethod.POST,
                new HttpEntity<>(PaymentMethodCreateRequestDTO.builder()
                        .methodType(PaymentMethodType.CARD)
                        .providerToken("tok_piv2_" + ts)
                        .provider("razorpay").last4("4242").brand("Visa")
                        .makeDefault(true).build(), authHeaders()),
                PaymentMethodResponseDTO.class).getBody();
        assertThat(pm).isNotNull();
        paymentMethodId = pm.getId();

        // Merchant 2 for tenant isolation tests
        MerchantResponseDTO m2 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder()
                        .merchantCode("PIV2_M2_" + ts).legalName("PI V2 Tenant 2")
                        .displayName("PIV2-2").supportEmail("piv2m2_" + ts + "@test.com")
                        .defaultCurrency("INR").countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m2.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchant2Id = m2.getId();

        CustomerResponseDTO c2 = rest.exchange(
                base() + "/api/v2/merchants/" + merchant2Id + "/customers",
                HttpMethod.POST,
                new HttpEntity<>(CustomerCreateRequestDTO.builder()
                        .fullName("PI V2 Customer 2")
                        .email("piv2_cust2_" + ts + "@test.com")
                        .phone("+91-9222222222")
                        .billingAddress("2 PI Street").build(), authHeaders()),
                CustomerResponseDTO.class).getBody();
        assertThat(c2).isNotNull();
        customer2Id = c2.getId();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

    private String piUrl(Long mId) {
        return base() + "/api/v2/merchants/" + mId + "/payment-intents";
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(adminToken);
        return h;
    }

    private HttpHeaders authHeadersWithIdempotency(String key) {
        HttpHeaders h = authHeaders();
        h.set("Idempotency-Key", key);
        return h;
    }

    /** Creates a payment intent with the given payment method attached. */
    private PaymentIntentV2ResponseDTO createIntentWithPM() {
        PaymentIntentCreateRequestDTO req = PaymentIntentCreateRequestDTO.builder()
                .customerId(customerId)
                .amount(new BigDecimal("1500.00"))
                .currency("INR")
                .captureMode(CaptureMode.AUTO)
                .paymentMethodId(paymentMethodId)
                .build();
        ResponseEntity<PaymentIntentV2ResponseDTO> resp = rest.exchange(
                piUrl(merchantId), HttpMethod.POST,
                new HttpEntity<>(req, authHeadersWithIdempotency("key-" + System.nanoTime())),
                PaymentIntentV2ResponseDTO.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    /** Confirms an intent. */
    private PaymentIntentV2ResponseDTO confirmIntent(Long intentId) {
        PaymentIntentConfirmRequestDTO req = PaymentIntentConfirmRequestDTO.builder()
                .gatewayName("razorpay")
                .build();
        ResponseEntity<PaymentIntentV2ResponseDTO> resp = rest.exchange(
                piUrl(merchantId) + "/" + intentId + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(req, authHeaders()),
                PaymentIntentV2ResponseDTO.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /payment-intents")
    class CreateTests {

        @Test
        @DisplayName("201 — with payment method -> REQUIRES_CONFIRMATION, clientSecret non-null")
        void withPM_201_requiresConfirmation() {
            PaymentIntentV2ResponseDTO body = createIntentWithPM();

            assertThat(body.getId()).isNotNull();
            assertThat(body.getMerchantId()).isEqualTo(merchantId);
            assertThat(body.getCustomerId()).isEqualTo(customerId);
            assertThat(body.getPaymentMethodId()).isEqualTo(paymentMethodId);
            assertThat(body.getStatus()).isEqualTo(PaymentIntentStatusV2.REQUIRES_CONFIRMATION);
            assertThat(body.getClientSecret()).isNotNull().hasSize(64);
        }

        @Test
        @DisplayName("201 — without payment method -> REQUIRES_PAYMENT_METHOD")
        void withoutPM_201_requiresPaymentMethod() {
            PaymentIntentCreateRequestDTO req = PaymentIntentCreateRequestDTO.builder()
                    .customerId(customerId)
                    .amount(new BigDecimal("500.00"))
                    .currency("INR")
                    .captureMode(CaptureMode.AUTO)
                    .build(); // no paymentMethodId
            ResponseEntity<PaymentIntentV2ResponseDTO> resp = rest.exchange(
                    piUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(req, authHeaders()),
                    PaymentIntentV2ResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getStatus())
                    .isEqualTo(PaymentIntentStatusV2.REQUIRES_PAYMENT_METHOD);
        }

        @Test
        @DisplayName("201 — same idempotency key returns existing intent with same ID")
        void idempotentKey_returnsSameId() {
            String idempotencyKey = "idem-create-" + System.nanoTime();
            PaymentIntentCreateRequestDTO req = PaymentIntentCreateRequestDTO.builder()
                    .customerId(customerId)
                    .amount(new BigDecimal("999.00"))
                    .currency("INR")
                    .captureMode(CaptureMode.AUTO)
                    .paymentMethodId(paymentMethodId)
                    .build();

            // First request
            ResponseEntity<PaymentIntentV2ResponseDTO> first = rest.exchange(
                    piUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(req, authHeadersWithIdempotency(idempotencyKey)),
                    PaymentIntentV2ResponseDTO.class);
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Long firstId = first.getBody().getId();

            // Second request with same key
            ResponseEntity<PaymentIntentV2ResponseDTO> second = rest.exchange(
                    piUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(req, authHeadersWithIdempotency(idempotencyKey)),
                    PaymentIntentV2ResponseDTO.class);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(second.getBody().getId()).isEqualTo(firstId);
        }

        @Test
        @DisplayName("401 — unauthenticated")
        void unauthenticated_401() {
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    piUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(PaymentIntentCreateRequestDTO.builder()
                            .customerId(customerId)
                            .amount(new BigDecimal("100.00"))
                            .currency("INR").captureMode(CaptureMode.AUTO).build(), jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ── Get ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /payment-intents/{id}")
    class GetTests {

        @Test
        @DisplayName("200 — returns intent details")
        void get_200() {
            PaymentIntentV2ResponseDTO created = createIntentWithPM();

            ResponseEntity<PaymentIntentV2ResponseDTO> resp = rest.exchange(
                    piUrl(merchantId) + "/" + created.getId(),
                    HttpMethod.GET, new HttpEntity<>(authHeaders()),
                    PaymentIntentV2ResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getId()).isEqualTo(created.getId());
            assertThat(resp.getBody().getStatus())
                    .isEqualTo(PaymentIntentStatusV2.REQUIRES_CONFIRMATION);
        }

        @Test
        @DisplayName("404 — cross-merchant access blocked (tenant isolation)")
        void tenantIsolation_404() {
            PaymentIntentV2ResponseDTO created = createIntentWithPM();

            // Attempt to access merchant 1's intent via merchant 2's URL
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    piUrl(merchant2Id) + "/" + created.getId(),
                    HttpMethod.GET, new HttpEntity<>(authHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── Confirm ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /payment-intents/{id}/confirm")
    class ConfirmTests {

        @Test
        @DisplayName("200 — intent transitions, attempt created")
        void confirm_200() {
            PaymentIntentV2ResponseDTO intent = createIntentWithPM();

            PaymentIntentV2ResponseDTO confirmed = confirmIntent(intent.getId());

            assertThat(confirmed.getStatus()).isEqualTo(PaymentIntentStatusV2.SUCCEEDED);
        }

        @Test
        @DisplayName("200 — confirm on already-SUCCEEDED intent returns current snapshot (idempotent)")
        void confirmIdempotent_returnsSucceededSnapshot() {
            PaymentIntentV2ResponseDTO intent = createIntentWithPM();
            confirmIntent(intent.getId()); // first confirm → SUCCEEDED

            // Second confirm → should return SUCCEEDED snapshot, not throw
            PaymentIntentConfirmRequestDTO req = PaymentIntentConfirmRequestDTO.builder()
                    .gatewayName("razorpay").build();
            ResponseEntity<PaymentIntentV2ResponseDTO> resp = rest.exchange(
                    piUrl(merchantId) + "/" + intent.getId() + "/confirm",
                    HttpMethod.POST, new HttpEntity<>(req, authHeaders()),
                    PaymentIntentV2ResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getStatus()).isEqualTo(PaymentIntentStatusV2.SUCCEEDED);
        }

        @Test
        @DisplayName("422 — confirm on CANCELLED intent")
        void confirmCancelled_422() {
            PaymentIntentV2ResponseDTO intent = createIntentWithPM();
            // Cancel first
            rest.exchange(piUrl(merchantId) + "/" + intent.getId() + "/cancel",
                    HttpMethod.POST, new HttpEntity<>(authHeaders()),
                    PaymentIntentV2ResponseDTO.class);

            // Try to confirm
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    piUrl(merchantId) + "/" + intent.getId() + "/confirm",
                    HttpMethod.POST, new HttpEntity<>(
                            PaymentIntentConfirmRequestDTO.builder().gatewayName("razorpay").build(),
                            authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /payment-intents/{id}/cancel")
    class CancelTests {

        @Test
        @DisplayName("200 — intent transitions to CANCELLED")
        void cancel_200() {
            PaymentIntentV2ResponseDTO intent = createIntentWithPM();

            ResponseEntity<PaymentIntentV2ResponseDTO> resp = rest.exchange(
                    piUrl(merchantId) + "/" + intent.getId() + "/cancel",
                    HttpMethod.POST, new HttpEntity<>(authHeaders()),
                    PaymentIntentV2ResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getStatus()).isEqualTo(PaymentIntentStatusV2.CANCELLED);
        }

        @Test
        @DisplayName("422 — cancel on already-SUCCEEDED intent")
        void cancelSucceeded_422() {
            PaymentIntentV2ResponseDTO intent = createIntentWithPM();
            confirmIntent(intent.getId()); // → SUCCEEDED

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    piUrl(merchantId) + "/" + intent.getId() + "/cancel",
                    HttpMethod.POST, new HttpEntity<>(authHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    // ── Attempts ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /payment-intents/{id}/attempts")
    class AttemptsTests {

        @Test
        @DisplayName("200 — returns all attempts in order")
        void listAttempts_200() {
            PaymentIntentV2ResponseDTO intent = createIntentWithPM();
            confirmIntent(intent.getId()); // creates attempt #1

            ResponseEntity<List<PaymentAttemptResponseDTO>> resp = rest.exchange(
                    piUrl(merchantId) + "/" + intent.getId() + "/attempts",
                    HttpMethod.GET, new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<List<PaymentAttemptResponseDTO>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<PaymentAttemptResponseDTO> attempts = resp.getBody();
            assertThat(attempts).hasSize(1);
            assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
            assertThat(attempts.get(0).getGatewayName()).isEqualTo("razorpay");
            assertThat(attempts.get(0).getStatus()).isEqualTo(PaymentAttemptStatus.CAPTURED);
        }

        @Test
        @DisplayName("200 — returns empty list when no attempts")
        void listAttempts_empty() {
            PaymentIntentV2ResponseDTO intent = createIntentWithPM();

            ResponseEntity<List<PaymentAttemptResponseDTO>> resp = rest.exchange(
                    piUrl(merchantId) + "/" + intent.getId() + "/attempts",
                    HttpMethod.GET, new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<List<PaymentAttemptResponseDTO>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).isEmpty();
        }
    }
}
