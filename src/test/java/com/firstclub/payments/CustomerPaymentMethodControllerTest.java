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
import com.firstclub.payments.dto.PaymentMethodResponseDTO;
import com.firstclub.payments.entity.PaymentMethodStatus;
import com.firstclub.payments.entity.PaymentMethodType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for CustomerPaymentMethodController.
 * Runs against Testcontainers Postgres; skipped without Docker.
 */
@DisplayName("CustomerPaymentMethod Controller - Integration Tests")
class CustomerPaymentMethodControllerTest extends com.firstclub.membership.PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private MerchantService merchantService;

    private String adminToken;
    private Long merchantId;
    private Long customerId;
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
        String code1 = "PM_M1_" + System.nanoTime();
        MerchantResponseDTO m1 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder().merchantCode(code1)
                        .legalName("PM Tenant One").displayName("PM1").supportEmail("pm1@test.com")
                        .defaultCurrency("INR").countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m1.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchantId = m1.getId();

        CustomerResponseDTO c1 = rest.exchange(
                base() + "/api/v2/merchants/" + merchantId + "/customers",
                HttpMethod.POST,
                new HttpEntity<>(CustomerCreateRequestDTO.builder()
                        .fullName("PM Customer").email("pm_cust_" + System.nanoTime() + "@test.com")
                        .phone("+91-9999999901").billingAddress("1 PM Street").build(),
                        authHeaders()),
                CustomerResponseDTO.class).getBody();
        assertThat(c1).isNotNull();
        customerId = c1.getId();

        // Merchant 2 (tenant isolation)
        String code2 = "PM_M2_" + System.nanoTime();
        MerchantResponseDTO m2 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder().merchantCode(code2)
                        .legalName("PM Tenant Two").displayName("PM2").supportEmail("pm2@test.com")
                        .defaultCurrency("INR").countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m2.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchant2Id = m2.getId();

        CustomerResponseDTO c2 = rest.exchange(
                base() + "/api/v2/merchants/" + merchant2Id + "/customers",
                HttpMethod.POST,
                new HttpEntity<>(CustomerCreateRequestDTO.builder()
                        .fullName("PM Customer 2").email("pm_cust2_" + System.nanoTime() + "@test.com")
                        .phone("+91-8888888802").billingAddress("2 PM Street").build(),
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

    private PaymentMethodResponseDTO createCardMethod(String tokenSuffix) {
        PaymentMethodCreateRequestDTO req = PaymentMethodCreateRequestDTO.builder()
                .methodType(PaymentMethodType.CARD)
                .providerToken("tok_visa_" + tokenSuffix)
                .provider("razorpay")
                .last4("4242")
                .brand("Visa")
                .makeDefault(false)
                .build();
        ResponseEntity<PaymentMethodResponseDTO> resp = rest.exchange(
                pmUrl(merchantId, customerId), HttpMethod.POST,
                new HttpEntity<>(req, authHeaders()),
                PaymentMethodResponseDTO.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    // ── CreateTests ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /payment-methods")
    class CreateTests {

        @Test
        @DisplayName("201 — first payment method is auto-set as default")
        void firstMethodAutoDefault() {
            PaymentMethodCreateRequestDTO req = PaymentMethodCreateRequestDTO.builder()
                    .methodType(PaymentMethodType.CARD)
                    .providerToken("tok_visa_auto_" + System.nanoTime())
                    .provider("razorpay").last4("4242").brand("Visa").makeDefault(false).build();

            ResponseEntity<PaymentMethodResponseDTO> resp = rest.exchange(
                    pmUrl(merchantId, customerId), HttpMethod.POST,
                    new HttpEntity<>(req, authHeaders()),
                    PaymentMethodResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            PaymentMethodResponseDTO body = resp.getBody();
            assertThat(body.getMerchantId()).isEqualTo(merchantId);
            assertThat(body.getCustomerId()).isEqualTo(customerId);
            assertThat(body.getStatus()).isEqualTo(PaymentMethodStatus.ACTIVE);
            assertThat(body.isDefault()).isTrue(); // first method auto-defaults
        }

        @Test
        @DisplayName("409 — duplicate provider token")
        void duplicateToken() {
            String token = "tok_dup_" + System.nanoTime();
            PaymentMethodCreateRequestDTO req = PaymentMethodCreateRequestDTO.builder()
                    .methodType(PaymentMethodType.CARD).providerToken(token)
                    .provider("razorpay").makeDefault(false).build();

            rest.exchange(pmUrl(merchantId, customerId), HttpMethod.POST,
                    new HttpEntity<>(req, authHeaders()), PaymentMethodResponseDTO.class);

            ResponseEntity<Map<String, Object>> dup = rest.exchange(
                    pmUrl(merchantId, customerId), HttpMethod.POST,
                    new HttpEntity<>(req, authHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("401 — unauthenticated")
        void unauthenticated() {
            PaymentMethodCreateRequestDTO req = PaymentMethodCreateRequestDTO.builder()
                    .methodType(PaymentMethodType.CARD)
                    .providerToken("tok_unauth_" + System.nanoTime())
                    .provider("razorpay").makeDefault(false).build();

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    pmUrl(merchantId, customerId), HttpMethod.POST,
                    new HttpEntity<>(req, jsonHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ── ListTests ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /payment-methods")
    class ListTests {

        @Test
        @DisplayName("200 — returns registered payment methods")
        void listReturnsResults() {
            createCardMethod(System.nanoTime() + "_a");
            createCardMethod(System.nanoTime() + "_b");

            ResponseEntity<List<PaymentMethodResponseDTO>> resp = rest.exchange(
                    pmUrl(merchantId, customerId), HttpMethod.GET,
                    new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    // ── SetDefaultTests ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /payment-methods/{id}/default")
    class SetDefaultTests {

        @Test
        @DisplayName("200 — setting new default clears previous default")
        void setDefaultClearsPrevious() {
            PaymentMethodResponseDTO first = createCardMethod(System.nanoTime() + "_first");
            assertThat(first.isDefault()).isTrue();

            PaymentMethodResponseDTO second = rest.exchange(
                    pmUrl(merchantId, customerId), HttpMethod.POST,
                    new HttpEntity<>(PaymentMethodCreateRequestDTO.builder()
                            .methodType(PaymentMethodType.UPI)
                            .providerToken("upi_tok_" + System.nanoTime())
                            .provider("razorpay").makeDefault(false).build(),
                            authHeaders()),
                    PaymentMethodResponseDTO.class).getBody();
            assertThat(second).isNotNull();

            // Set second as default
            ResponseEntity<PaymentMethodResponseDTO> setResp = rest.exchange(
                    pmUrl(merchantId, customerId) + "/" + second.getId() + "/default",
                    HttpMethod.POST,
                    new HttpEntity<>(authHeaders()),
                    PaymentMethodResponseDTO.class);

            assertThat(setResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(setResp.getBody().isDefault()).isTrue();
            assertThat(setResp.getBody().getId()).isEqualTo(second.getId());
        }
    }

    // ── RevokeTests ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /payment-methods/{id}")
    class RevokeTests {

        @Test
        @DisplayName("200 — revoked method has REVOKED status")
        void revokeSuccess() {
            PaymentMethodResponseDTO pm = createCardMethod(System.nanoTime() + "_rev");

            ResponseEntity<PaymentMethodResponseDTO> resp = rest.exchange(
                    pmUrl(merchantId, customerId) + "/" + pm.getId(),
                    HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders()),
                    PaymentMethodResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getStatus()).isEqualTo(PaymentMethodStatus.REVOKED);
        }
    }

    // ── TenantIsolationTests ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Tenant isolation")
    class TenantIsolationTests {

        @Test
        @DisplayName("404 — payment method from merchant1 not visible under merchant2")
        void crossMerchantNotFound() {
            PaymentMethodResponseDTO pm = createCardMethod(System.nanoTime() + "_iso");

            // Access merchant1's payment method route using merchant2's path
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    pmUrl(merchant2Id, customer2Id) + "/" + pm.getId() + "/default",
                    HttpMethod.POST,
                    new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
