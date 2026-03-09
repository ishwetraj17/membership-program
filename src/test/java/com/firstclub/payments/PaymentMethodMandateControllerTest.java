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
import com.firstclub.payments.dto.PaymentMethodCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodMandateCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodMandateResponseDTO;
import com.firstclub.payments.dto.PaymentMethodResponseDTO;
import com.firstclub.payments.entity.MandateStatus;
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

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for PaymentMethodMandateController.
 * Runs against Testcontainers Postgres; skipped without Docker.
 */
@DisplayName("PaymentMethodMandate Controller - Integration Tests")
class PaymentMethodMandateControllerTest extends com.firstclub.membership.PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private MerchantService merchantService;

    private String adminToken;
    private Long merchantId;
    private Long customerId;
    private Long cardPaymentMethodId;
    private Long upiPaymentMethodId;
    private Long merchant2Id;
    private Long customer2Id;

    // ── Setup ─────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        // Auth
        LoginRequestDTO login = LoginRequestDTO.builder()
                .email(ADMIN_EMAIL).password(ADMIN_PASSWORD).build();
        ResponseEntity<JwtResponseDTO> auth = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new HttpEntity<>(login, jsonHeaders()),
                JwtResponseDTO.class);
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminToken = auth.getBody().getToken();

        // Merchant 1
        String code1 = "MND_M1_" + System.nanoTime();
        MerchantResponseDTO m1 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder().merchantCode(code1)
                        .legalName("Mandate Tenant One").displayName("MND1")
                        .supportEmail("mnd1@test.com").defaultCurrency("INR")
                        .countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m1.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchantId = m1.getId();

        CustomerResponseDTO c1 = rest.exchange(
                base() + "/api/v2/merchants/" + merchantId + "/customers",
                HttpMethod.POST,
                new HttpEntity<>(CustomerCreateRequestDTO.builder()
                        .fullName("Mandate Customer").email("mnd_cust_" + System.nanoTime() + "@test.com")
                        .phone("+91-9999999801").billingAddress("1 Mandate St").build(),
                        authHeaders()),
                CustomerResponseDTO.class).getBody();
        assertThat(c1).isNotNull();
        customerId = c1.getId();

        // CARD payment method (supports mandates)
        PaymentMethodResponseDTO cardPm = rest.exchange(
                pmUrl(merchantId, customerId), HttpMethod.POST,
                new HttpEntity<>(PaymentMethodCreateRequestDTO.builder()
                        .methodType(PaymentMethodType.CARD)
                        .providerToken("tok_card_mnd_" + System.nanoTime())
                        .provider("razorpay").last4("4242").brand("Visa").makeDefault(true).build(),
                        authHeaders()),
                PaymentMethodResponseDTO.class).getBody();
        assertThat(cardPm).isNotNull();
        cardPaymentMethodId = cardPm.getId();

        // UPI payment method (does NOT support mandates)
        PaymentMethodResponseDTO upiPm = rest.exchange(
                pmUrl(merchantId, customerId), HttpMethod.POST,
                new HttpEntity<>(PaymentMethodCreateRequestDTO.builder()
                        .methodType(PaymentMethodType.UPI)
                        .providerToken("upi_tok_mnd_" + System.nanoTime())
                        .provider("razorpay").makeDefault(false).build(),
                        authHeaders()),
                PaymentMethodResponseDTO.class).getBody();
        assertThat(upiPm).isNotNull();
        upiPaymentMethodId = upiPm.getId();

        // Merchant 2 (tenant isolation)
        String code2 = "MND_M2_" + System.nanoTime();
        MerchantResponseDTO m2 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder().merchantCode(code2)
                        .legalName("Mandate Tenant Two").displayName("MND2")
                        .supportEmail("mnd2@test.com").defaultCurrency("INR")
                        .countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m2.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchant2Id = m2.getId();

        CustomerResponseDTO c2 = rest.exchange(
                base() + "/api/v2/merchants/" + merchant2Id + "/customers",
                HttpMethod.POST,
                new HttpEntity<>(CustomerCreateRequestDTO.builder()
                        .fullName("Mandate Customer 2").email("mnd_cust2_" + System.nanoTime() + "@test.com")
                        .phone("+91-8888888802").billingAddress("2 Mandate St").build(),
                        authHeaders()),
                CustomerResponseDTO.class).getBody();
        assertThat(c2).isNotNull();
        customer2Id = c2.getId();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

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

    private String pmUrl(Long mId, Long cId) {
        return base() + "/api/v2/merchants/" + mId + "/customers/" + cId + "/payment-methods";
    }

    private String mandateUrl(Long mId, Long cId, Long pmId) {
        return pmUrl(mId, cId) + "/" + pmId + "/mandates";
    }

    private PaymentMethodMandateCreateRequestDTO defaultMandateRequest() {
        return PaymentMethodMandateCreateRequestDTO.builder()
                .mandateReference("NACH_" + System.nanoTime())
                .maxAmount(new BigDecimal("5000.00"))
                .currency("INR")
                .build();
    }

    private PaymentMethodMandateResponseDTO createMandate() {
        ResponseEntity<PaymentMethodMandateResponseDTO> resp = rest.exchange(
                mandateUrl(merchantId, customerId, cardPaymentMethodId), HttpMethod.POST,
                new HttpEntity<>(defaultMandateRequest(), authHeaders()),
                PaymentMethodMandateResponseDTO.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    // ── CreateMandateTests ────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /mandates")
    class CreateMandateTests {

        @Test
        @DisplayName("201 — CARD type creates PENDING mandate")
        void cardTypeSuccess() {
            PaymentMethodMandateResponseDTO body = createMandate();

            assertThat(body.getPaymentMethodId()).isEqualTo(cardPaymentMethodId);
            assertThat(body.getStatus()).isEqualTo(MandateStatus.PENDING);
            assertThat(body.getCurrency()).isEqualTo("INR");
            assertThat(body.getMaxAmount()).isEqualByComparingTo("5000.00");
        }

        @Test
        @DisplayName("400 — UPI type does not support mandates")
        void upiTypeUnsupported() {
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    mandateUrl(merchantId, customerId, upiPaymentMethodId), HttpMethod.POST,
                    new HttpEntity<>(defaultMandateRequest(), authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody()).containsEntry("errorCode", "UNSUPPORTED_MANDATE_METHOD_TYPE");
        }

        @Test
        @DisplayName("401 — unauthenticated")
        void unauthenticated() {
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    mandateUrl(merchantId, customerId, cardPaymentMethodId), HttpMethod.POST,
                    new HttpEntity<>(defaultMandateRequest(), jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ── ListMandatesTests ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /mandates")
    class ListMandatesTests {

        @Test
        @DisplayName("200 — lists created mandates")
        void listSuccess() {
            createMandate();
            createMandate();

            ResponseEntity<List<PaymentMethodMandateResponseDTO>> resp = rest.exchange(
                    mandateUrl(merchantId, customerId, cardPaymentMethodId), HttpMethod.GET,
                    new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    // ── RevokeMandateTests ────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /mandates/{id}/revoke")
    class RevokeMandateTests {

        @Test
        @DisplayName("200 — PENDING mandate revoked successfully")
        void revokeSuccess() {
            PaymentMethodMandateResponseDTO mandate = createMandate();

            ResponseEntity<PaymentMethodMandateResponseDTO> resp = rest.exchange(
                    mandateUrl(merchantId, customerId, cardPaymentMethodId)
                            + "/" + mandate.getId() + "/revoke",
                    HttpMethod.POST,
                    new HttpEntity<>(authHeaders()),
                    PaymentMethodMandateResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getStatus()).isEqualTo(MandateStatus.REVOKED);
            assertThat(resp.getBody().getRevokedAt()).isNotNull();
        }

        @Test
        @DisplayName("400 — revoking an already-revoked mandate")
        void doubleRevoke() {
            PaymentMethodMandateResponseDTO mandate = createMandate();
            String revokeUrl = mandateUrl(merchantId, customerId, cardPaymentMethodId)
                    + "/" + mandate.getId() + "/revoke";

            // First revoke
            rest.exchange(revokeUrl, HttpMethod.POST,
                    new HttpEntity<>(authHeaders()), PaymentMethodMandateResponseDTO.class);

            // Second revoke
            ResponseEntity<Map<String, Object>> resp = rest.exchange(revokeUrl, HttpMethod.POST,
                    new HttpEntity<>(authHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody()).containsEntry("errorCode", "MANDATE_ALREADY_REVOKED");
        }
    }

    // ── TenantIsolationTests ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Tenant isolation")
    class TenantIsolationTests {

        @Test
        @DisplayName("404 — payment method from merchant1 not visible under merchant2")
        void crossMerchantNotFound() {
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    mandateUrl(merchant2Id, customer2Id, cardPaymentMethodId), HttpMethod.POST,
                    new HttpEntity<>(defaultMandateRequest(), authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
