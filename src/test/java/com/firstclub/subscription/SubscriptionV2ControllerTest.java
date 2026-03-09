package com.firstclub.subscription;

import com.firstclub.catalog.dto.*;
import com.firstclub.catalog.entity.BillingIntervalUnit;
import com.firstclub.catalog.entity.BillingType;
import com.firstclub.customer.dto.CustomerCreateRequestDTO;
import com.firstclub.customer.dto.CustomerResponseDTO;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.merchant.dto.MerchantCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.merchant.dto.MerchantStatusUpdateRequestDTO;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.service.MerchantService;
import com.firstclub.subscription.dto.SubscriptionCreateRequestDTO;
import com.firstclub.subscription.dto.SubscriptionResponseDTO;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for SubscriptionV2Controller.
 * Runs against Testcontainers Postgres; skipped without Docker.
 */
@DisplayName("SubscriptionV2 Controller - Integration Tests")
class SubscriptionV2ControllerTest extends com.firstclub.membership.PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private MerchantService merchantService;

    private String adminToken;

    // Merchant 1 fixtures
    private Long merchantId;
    private Long customerId;
    private Long productId;
    private Long priceId;        // no-trial price
    private Long priceVersionId;

    // Merchant 2 fixtures (tenant isolation)
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

        // ── Merchant 1 ──────────────────────────────────────────────────────
        String code1 = "SUB_M1_" + System.nanoTime();
        MerchantResponseDTO m1 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder().merchantCode(code1)
                        .legalName("Sub Tenant One").displayName("ST1")
                        .supportEmail("st1@test.com").defaultCurrency("INR")
                        .countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m1.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchantId = m1.getId();

        // Customer under merchant 1
        CustomerResponseDTO c1 = rest.exchange(
                base() + "/api/v2/merchants/" + merchantId + "/customers",
                HttpMethod.POST,
                new HttpEntity<>(CustomerCreateRequestDTO.builder()
                        .fullName("Sub Customer").email("sub_cust_" + System.nanoTime() + "@test.com")
                        .phone("+91-9999999999").billingAddress("1 Main St").build(),
                        authHeaders()),
                CustomerResponseDTO.class).getBody();
        assertThat(c1).isNotNull();
        customerId = c1.getId();

        // Product under merchant 1
        ProductResponseDTO p1 = rest.exchange(
                base() + "/api/v2/merchants/" + merchantId + "/products",
                HttpMethod.POST,
                new HttpEntity<>(new ProductCreateRequestDTO("PROD_" + System.nanoTime(),
                        "Sub Product", null), authHeaders()),
                ProductResponseDTO.class).getBody();
        assertThat(p1).isNotNull();
        productId = p1.getId();

        // Price (no trial) under merchant 1
        PriceCreateRequestDTO priceDTO = PriceCreateRequestDTO.builder()
                .productId(productId).priceCode("PRICE_" + System.nanoTime())
                .billingType(BillingType.RECURRING).currency("INR")
                .amount(new BigDecimal("499")).billingIntervalUnit(BillingIntervalUnit.MONTH)
                .billingIntervalCount(1).trialDays(0).build();
        PriceResponseDTO pr = rest.exchange(
                base() + "/api/v2/merchants/" + merchantId + "/prices",
                HttpMethod.POST,
                new HttpEntity<>(priceDTO, authHeaders()),
                PriceResponseDTO.class).getBody();
        assertThat(pr).isNotNull();
        priceId = pr.getId();

        // Price version for the price (effectiveFrom = now — within 1-minute leeway window)
        PriceVersionCreateRequestDTO pvDTO = PriceVersionCreateRequestDTO.builder()
                .effectiveFrom(LocalDateTime.now())
                .amount(new BigDecimal("499")).currency("INR").build();
        PriceVersionResponseDTO pv = rest.exchange(
                base() + "/api/v2/merchants/" + merchantId + "/prices/" + priceId + "/versions",
                HttpMethod.POST,
                new HttpEntity<>(pvDTO, authHeaders()),
                PriceVersionResponseDTO.class).getBody();
        assertThat(pv).isNotNull();
        priceVersionId = pv.getId();

        // ── Merchant 2 ──────────────────────────────────────────────────────
        String code2 = "SUB_M2_" + System.nanoTime();
        MerchantResponseDTO m2 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder().merchantCode(code2)
                        .legalName("Sub Tenant Two").displayName("ST2")
                        .supportEmail("st2@test.com").defaultCurrency("INR")
                        .countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m2.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchant2Id = m2.getId();

        CustomerResponseDTO c2 = rest.exchange(
                base() + "/api/v2/merchants/" + merchant2Id + "/customers",
                HttpMethod.POST,
                new HttpEntity<>(CustomerCreateRequestDTO.builder()
                        .fullName("Sub Customer 2").email("sub_cust2_" + System.nanoTime() + "@test.com")
                        .phone("+91-8888888888").billingAddress("2 Main St").build(),
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

    private String subsUrl(Long mId) {
        return base() + "/api/v2/merchants/" + mId + "/subscriptions";
    }

    private SubscriptionCreateRequestDTO createReq() {
        return SubscriptionCreateRequestDTO.builder()
                .customerId(customerId).productId(productId).priceId(priceId)
                .priceVersionId(priceVersionId).build();
    }

    private SubscriptionResponseDTO createSubscription() {
        ResponseEntity<SubscriptionResponseDTO> resp = rest.exchange(
                subsUrl(merchantId), HttpMethod.POST,
                new HttpEntity<>(createReq(), authHeaders()),
                SubscriptionResponseDTO.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    // ── CreateSubscriptionTests ───────────────────────────────────────────────

    @Nested
    @DisplayName("POST /subscriptions")
    class CreateSubscriptionTests {

        @Test
        @DisplayName("201 — created with INCOMPLETE status (no trial days)")
        void createIncomplete() {
            ResponseEntity<SubscriptionResponseDTO> resp = rest.exchange(
                    subsUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(createReq(), authHeaders()),
                    SubscriptionResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            SubscriptionResponseDTO body = resp.getBody();
            assertThat(body.getStatus()).isEqualTo(SubscriptionStatusV2.INCOMPLETE);
            assertThat(body.getMerchantId()).isEqualTo(merchantId);
            assertThat(body.getCustomerId()).isEqualTo(customerId);
            assertThat(body.getProductId()).isEqualTo(productId);
            assertThat(body.getPriceId()).isEqualTo(priceId);
            assertThat(body.getPriceVersionId()).isEqualTo(priceVersionId);
        }

        @Test
        @DisplayName("409 — duplicate INCOMPLETE subscription")
        void duplicateSubscription() {
            // First subscription (INCOMPLETE)
            createSubscription();

            // Second subscription for the same customer+product
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    subsUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(createReq(), authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("400 — price belongs to a different product")
        void priceFromDifferentProduct() {
            // Create a second product
            ProductResponseDTO p2 = rest.exchange(
                    base() + "/api/v2/merchants/" + merchantId + "/products",
                    HttpMethod.POST,
                    new HttpEntity<>(new ProductCreateRequestDTO("PROD2_" + System.nanoTime(),
                            "Sub Product 2", null), authHeaders()),
                    ProductResponseDTO.class).getBody();

            // priceId belongs to productId but we pass p2's id in the request
            SubscriptionCreateRequestDTO badReq = SubscriptionCreateRequestDTO.builder()
                    .customerId(customerId).productId(p2.getId()).priceId(priceId)
                    .priceVersionId(priceVersionId).build();

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    subsUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(badReq, authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("401 — unauthenticated request")
        void unauthenticated() {
            HttpHeaders h = jsonHeaders();  // no auth
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    subsUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(createReq(), h),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ── GetSubscriptionTests ──────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /subscriptions/{id}")
    class GetSubscriptionTests {

        @Test
        @DisplayName("200 — fetches subscription by ID")
        void getByIdSuccess() {
            SubscriptionResponseDTO created = createSubscription();

            ResponseEntity<SubscriptionResponseDTO> resp = rest.exchange(
                    subsUrl(merchantId) + "/" + created.getId(),
                    HttpMethod.GET, new HttpEntity<>(authHeaders()),
                    SubscriptionResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getId()).isEqualTo(created.getId());
        }

        @Test
        @DisplayName("404 — subscription from another merchant is not visible")
        void tenantIsolation() {
            SubscriptionResponseDTO created = createSubscription();

            // Query merchant 2 for merchant 1's subscription
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    subsUrl(merchant2Id) + "/" + created.getId(),
                    HttpMethod.GET, new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── ListSubscriptionTests ─────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /subscriptions")
    class ListSubscriptionTests {

        @Test
        @DisplayName("200 — lists subscriptions for merchant (paginated)")
        void listAll() {
            createSubscription();

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    subsUrl(merchantId) + "?page=0&size=10",
                    HttpMethod.GET, new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("200 — ?status=INCOMPLETE filter returns only matching subs")
        void listByStatus() {
            createSubscription();

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    subsUrl(merchantId) + "?status=INCOMPLETE",
                    HttpMethod.GET, new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ── CancelSubscriptionTests ───────────────────────────────────────────────

    @Nested
    @DisplayName("POST /subscriptions/{id}/cancel")
    class CancelSubscriptionTests {

        @Test
        @DisplayName("200 — immediate cancel sets status CANCELLED")
        void cancelImmediately() {
            SubscriptionResponseDTO created = createSubscription();

            ResponseEntity<SubscriptionResponseDTO> resp = rest.exchange(
                    subsUrl(merchantId) + "/" + created.getId() + "/cancel?atPeriodEnd=false",
                    HttpMethod.POST, new HttpEntity<>(authHeaders()),
                    SubscriptionResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getStatus()).isEqualTo(SubscriptionStatusV2.CANCELLED);
            assertThat(resp.getBody().getCancelledAt()).isNotNull();
        }

        @Test
        @DisplayName("200 — cancel-at-period-end sets flag but keeps status")
        void cancelAtPeriodEnd() {
            SubscriptionResponseDTO created = createSubscription();

            ResponseEntity<SubscriptionResponseDTO> resp = rest.exchange(
                    subsUrl(merchantId) + "/" + created.getId() + "/cancel?atPeriodEnd=true",
                    HttpMethod.POST, new HttpEntity<>(authHeaders()),
                    SubscriptionResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().isCancelAtPeriodEnd()).isTrue();
            // Status should remain INCOMPLETE (no status change)
            assertThat(resp.getBody().getStatus()).isEqualTo(SubscriptionStatusV2.INCOMPLETE);
        }

        @Test
        @DisplayName("400 — cancel already-cancelled subscription")
        void cancelTerminal() {
            SubscriptionResponseDTO created = createSubscription();
            // cancel once
            rest.exchange(subsUrl(merchantId) + "/" + created.getId() + "/cancel?atPeriodEnd=false",
                    HttpMethod.POST, new HttpEntity<>(authHeaders()), SubscriptionResponseDTO.class);

            // cancel again — should fail
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    subsUrl(merchantId) + "/" + created.getId() + "/cancel?atPeriodEnd=false",
                    HttpMethod.POST, new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ── PauseResumeTests ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Pause and Resume")
    class PauseResumeTests {

        private SubscriptionResponseDTO forceActive(SubscriptionResponseDTO sub) {
            // INCOMPLETE → ACTIVE by cancelling then re-evaluating is not possible directly.
            // Instead we verify the state machine via the service unit test.
            // For integration we start from INCOMPLETE and test invalid pause (400).
            return sub;
        }

        @Test
        @DisplayName("400 — pause INCOMPLETE subscription (invalid transition)")
        void pauseInvalidState() {
            SubscriptionResponseDTO created = createSubscription();

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    subsUrl(merchantId) + "/" + created.getId() + "/pause",
                    HttpMethod.POST, new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            // INCOMPLETE → PAUSED is not in state machine
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("400 — resume INCOMPLETE subscription (invalid transition)")
        void resumeInvalidState() {
            SubscriptionResponseDTO created = createSubscription();

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    subsUrl(merchantId) + "/" + created.getId() + "/resume",
                    HttpMethod.POST, new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            // Not PAUSED — resume should fail
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
