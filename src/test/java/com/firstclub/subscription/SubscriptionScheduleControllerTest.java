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
import com.firstclub.subscription.dto.SubscriptionScheduleCreateRequestDTO;
import com.firstclub.subscription.dto.SubscriptionScheduleResponseDTO;
import com.firstclub.subscription.entity.SubscriptionScheduleStatus;
import com.firstclub.subscription.entity.SubscriptionScheduledAction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for SubscriptionScheduleController.
 * Runs against Testcontainers Postgres; skipped without Docker.
 */
@DisplayName("SubscriptionSchedule Controller - Integration Tests")
class SubscriptionScheduleControllerTest extends com.firstclub.membership.PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private MerchantService merchantService;

    private String adminToken;
    private Long merchantId;
    private Long merchant2Id;
    private Long subscriptionId;

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
        String code1 = "SCHED_M1_" + System.nanoTime();
        MerchantResponseDTO m1 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder().merchantCode(code1)
                        .legalName("Sched Tenant One").displayName("ScT1")
                        .supportEmail("sct1@test.com").defaultCurrency("INR")
                        .countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m1.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchantId = m1.getId();

        // Customer
        CustomerResponseDTO cust = rest.exchange(
                base() + "/api/v2/merchants/" + merchantId + "/customers",
                HttpMethod.POST,
                new HttpEntity<>(CustomerCreateRequestDTO.builder()
                        .fullName("Sched Customer").email("sched_" + System.nanoTime() + "@test.com")
                        .phone("+91-7777777777").billingAddress("1 Sched Rd").build(),
                        authHeaders()),
                CustomerResponseDTO.class).getBody();
        assertThat(cust).isNotNull();
        Long customerId = cust.getId();

        // Product
        ProductResponseDTO prod = rest.exchange(
                base() + "/api/v2/merchants/" + merchantId + "/products",
                HttpMethod.POST,
                new HttpEntity<>(new ProductCreateRequestDTO("SPROD_" + System.nanoTime(),
                        "Sched Product", null), authHeaders()),
                ProductResponseDTO.class).getBody();
        assertThat(prod).isNotNull();
        Long productId = prod.getId();

        // Price
        PriceResponseDTO price = rest.exchange(
                base() + "/api/v2/merchants/" + merchantId + "/prices",
                HttpMethod.POST,
                new HttpEntity<>(PriceCreateRequestDTO.builder()
                        .productId(productId).priceCode("SPRCE_" + System.nanoTime())
                        .billingType(BillingType.RECURRING).currency("INR")
                        .amount(new BigDecimal("299")).billingIntervalUnit(BillingIntervalUnit.MONTH)
                        .billingIntervalCount(1).trialDays(0).build(),
                        authHeaders()),
                PriceResponseDTO.class).getBody();
        assertThat(price).isNotNull();
        Long priceId = price.getId();

        // Price version
        PriceVersionResponseDTO pv = rest.exchange(
                base() + "/api/v2/merchants/" + merchantId + "/prices/" + priceId + "/versions",
                HttpMethod.POST,
                new HttpEntity<>(PriceVersionCreateRequestDTO.builder()
                        .effectiveFrom(LocalDateTime.now())
                        .amount(new BigDecimal("299")).currency("INR").build(),
                        authHeaders()),
                PriceVersionResponseDTO.class).getBody();
        assertThat(pv).isNotNull();

        // Subscription
        SubscriptionResponseDTO sub = rest.exchange(
                base() + "/api/v2/merchants/" + merchantId + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(SubscriptionCreateRequestDTO.builder()
                        .customerId(customerId).productId(productId).priceId(priceId)
                        .priceVersionId(pv.getId()).build(),
                        authHeaders()),
                SubscriptionResponseDTO.class).getBody();
        assertThat(sub).isNotNull();
        subscriptionId = sub.getId();

        // ── Merchant 2 ──────────────────────────────────────────────────────
        String code2 = "SCHED_M2_" + System.nanoTime();
        MerchantResponseDTO m2 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder().merchantCode(code2)
                        .legalName("Sched Tenant Two").displayName("ScT2")
                        .supportEmail("sct2@test.com").defaultCurrency("INR")
                        .countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m2.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchant2Id = m2.getId();
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

    private String schedulesUrl(Long mId, Long subId) {
        return base() + "/api/v2/merchants/" + mId + "/subscriptions/" + subId + "/schedules";
    }

    private SubscriptionScheduleCreateRequestDTO cancelScheduleReq() {
        return SubscriptionScheduleCreateRequestDTO.builder()
                .scheduledAction(SubscriptionScheduledAction.CANCEL)
                .effectiveAt(LocalDateTime.now().plusDays(30)).build();
    }

    // ── CreateScheduleTests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /schedules")
    class CreateScheduleTests {

        @Test
        @DisplayName("201 — CANCEL schedule created for future date")
        void createCancelSchedule() {
            ResponseEntity<SubscriptionScheduleResponseDTO> resp = rest.exchange(
                    schedulesUrl(merchantId, subscriptionId), HttpMethod.POST,
                    new HttpEntity<>(cancelScheduleReq(), authHeaders()),
                    SubscriptionScheduleResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            SubscriptionScheduleResponseDTO body = resp.getBody();
            assertThat(body.getScheduledAction()).isEqualTo(SubscriptionScheduledAction.CANCEL);
            assertThat(body.getStatus()).isEqualTo(SubscriptionScheduleStatus.SCHEDULED);
            assertThat(body.getSubscriptionId()).isEqualTo(subscriptionId);
        }

        @Test
        @DisplayName("400 — effectiveAt in the past is rejected")
        void pastEffectiveAt() {
            SubscriptionScheduleCreateRequestDTO req = SubscriptionScheduleCreateRequestDTO.builder()
                    .scheduledAction(SubscriptionScheduledAction.PAUSE)
                    .effectiveAt(LocalDateTime.now().minusDays(1)).build();

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    schedulesUrl(merchantId, subscriptionId), HttpMethod.POST,
                    new HttpEntity<>(req, authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("409 — duplicate SCHEDULED entry at same effectiveAt")
        void duplicateConflict() {
            LocalDateTime sameTime = LocalDateTime.now().plusDays(30);

            SubscriptionScheduleCreateRequestDTO first = SubscriptionScheduleCreateRequestDTO.builder()
                    .scheduledAction(SubscriptionScheduledAction.CANCEL)
                    .effectiveAt(sameTime).build();
            SubscriptionScheduleCreateRequestDTO second = SubscriptionScheduleCreateRequestDTO.builder()
                    .scheduledAction(SubscriptionScheduledAction.PAUSE)
                    .effectiveAt(sameTime).build();

            rest.exchange(schedulesUrl(merchantId, subscriptionId), HttpMethod.POST,
                    new HttpEntity<>(first, authHeaders()), SubscriptionScheduleResponseDTO.class);

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    schedulesUrl(merchantId, subscriptionId), HttpMethod.POST,
                    new HttpEntity<>(second, authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("404 — subscription from another merchant is not visible")
        void tenantIsolation() {
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    schedulesUrl(merchant2Id, subscriptionId), HttpMethod.POST,
                    new HttpEntity<>(cancelScheduleReq(), authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── ListSchedulesTests ────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /schedules")
    class ListScheduleTests {

        @Test
        @DisplayName("200 — lists schedules for subscription")
        void listSchedules() {
            rest.exchange(schedulesUrl(merchantId, subscriptionId), HttpMethod.POST,
                    new HttpEntity<>(cancelScheduleReq(), authHeaders()),
                    SubscriptionScheduleResponseDTO.class);

            ResponseEntity<List> resp = rest.exchange(
                    schedulesUrl(merchantId, subscriptionId), HttpMethod.GET,
                    new HttpEntity<>(authHeaders()), List.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).hasSize(1);
        }

        @Test
        @DisplayName("404 — listing schedules for another merchant's subscription")
        void listTenantIsolation() {
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    schedulesUrl(merchant2Id, subscriptionId), HttpMethod.GET,
                    new HttpEntity<>(authHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── CancelScheduleTests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /schedules/{scheduleId}")
    class CancelScheduleTests {

        @Test
        @DisplayName("200 — cancel SCHEDULED entry → CANCELLED")
        void cancelScheduled() {
            SubscriptionScheduleResponseDTO created = rest.exchange(
                    schedulesUrl(merchantId, subscriptionId), HttpMethod.POST,
                    new HttpEntity<>(cancelScheduleReq(), authHeaders()),
                    SubscriptionScheduleResponseDTO.class).getBody();

            ResponseEntity<SubscriptionScheduleResponseDTO> resp = rest.exchange(
                    schedulesUrl(merchantId, subscriptionId) + "/" + created.getId(),
                    HttpMethod.DELETE, new HttpEntity<>(authHeaders()),
                    SubscriptionScheduleResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getStatus()).isEqualTo(SubscriptionScheduleStatus.CANCELLED);
        }

        @Test
        @DisplayName("400 — cancel already-CANCELLED schedule")
        void cancelAlreadyCancelled() {
            SubscriptionScheduleResponseDTO created = rest.exchange(
                    schedulesUrl(merchantId, subscriptionId), HttpMethod.POST,
                    new HttpEntity<>(cancelScheduleReq(), authHeaders()),
                    SubscriptionScheduleResponseDTO.class).getBody();

            // Cancel once
            rest.exchange(schedulesUrl(merchantId, subscriptionId) + "/" + created.getId(),
                    HttpMethod.DELETE, new HttpEntity<>(authHeaders()),
                    SubscriptionScheduleResponseDTO.class);

            // Cancel again
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    schedulesUrl(merchantId, subscriptionId) + "/" + created.getId(),
                    HttpMethod.DELETE, new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("404 — schedule does not exist")
        void scheduleNotFound() {
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    schedulesUrl(merchantId, subscriptionId) + "/999999",
                    HttpMethod.DELETE, new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("401 — unauthenticated request")
        void unauthenticated() {
            SubscriptionScheduleResponseDTO created = rest.exchange(
                    schedulesUrl(merchantId, subscriptionId), HttpMethod.POST,
                    new HttpEntity<>(cancelScheduleReq(), authHeaders()),
                    SubscriptionScheduleResponseDTO.class).getBody();

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    schedulesUrl(merchantId, subscriptionId) + "/" + created.getId(),
                    HttpMethod.DELETE, new HttpEntity<>(jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
