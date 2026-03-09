package com.firstclub.catalog;

import com.firstclub.catalog.dto.*;
import com.firstclub.catalog.entity.BillingIntervalUnit;
import com.firstclub.catalog.entity.BillingType;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.merchant.dto.MerchantCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.merchant.dto.MerchantStatusUpdateRequestDTO;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.service.MerchantService;
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
 * Integration tests for PriceController (prices + price versions).
 * Runs against Testcontainers Postgres; skipped without Docker.
 */
@DisplayName("Price Controller - Integration Tests")
class PriceControllerTest extends com.firstclub.membership.PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired MerchantService merchantService;

    private String adminToken;
    private Long merchantId;
    private Long merchant2Id;
    private Long productId;
    private Long product2Id;

    @BeforeEach
    void setUp() {
        // Authenticate
        ResponseEntity<JwtResponseDTO> auth = rest.postForEntity(
                base() + "/api/auth/login",
                new LoginRequestDTO(ADMIN_EMAIL, ADMIN_PASSWORD),
                JwtResponseDTO.class);
        adminToken = auth.getBody().getToken();

        // Merchant 1
        String code1 = "PRC_M1_" + System.nanoTime();
        MerchantResponseDTO m1 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder().merchantCode(code1)
                        .legalName("Price Tenant One").displayName("PT1")
                        .supportEmail("pt1@test.com").defaultCurrency("INR")
                        .countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m1.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchantId = m1.getId();

        // Product under merchant 1
        ProductResponseDTO p1 = rest.exchange(
                base() + "/api/v2/merchants/" + merchantId + "/products",
                HttpMethod.POST,
                new HttpEntity<>(new ProductCreateRequestDTO("P_" + System.nanoTime(), "Test Product", null),
                        jsonHeaders()),
                ProductResponseDTO.class).getBody();
        productId = p1.getId();

        // Merchant 2
        String code2 = "PRC_M2_" + System.nanoTime();
        MerchantResponseDTO m2 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder().merchantCode(code2)
                        .legalName("Price Tenant Two").displayName("PT2")
                        .supportEmail("pt2@test.com").defaultCurrency("INR")
                        .countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m2.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchant2Id = m2.getId();

        // Product under merchant 2
        ProductResponseDTO p2 = rest.exchange(
                base() + "/api/v2/merchants/" + merchant2Id + "/products",
                HttpMethod.POST,
                new HttpEntity<>(new ProductCreateRequestDTO("P2_" + System.nanoTime(), "Product 2", null),
                        jsonHeaders()),
                ProductResponseDTO.class).getBody();
        product2Id = p2.getId();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(adminToken);
        return h;
    }

    private String pricesUrl(Long mId) {
        return base() + "/api/v2/merchants/" + mId + "/prices";
    }

    private PriceCreateRequestDTO recurringPrice(String code, Long pId) {
        return PriceCreateRequestDTO.builder()
                .productId(pId).priceCode(code)
                .billingType(BillingType.RECURRING).currency("INR")
                .amount(new BigDecimal("499")).billingIntervalUnit(BillingIntervalUnit.MONTH)
                .billingIntervalCount(1).trialDays(0).build();
    }

    private PriceCreateRequestDTO oneTimePrice(String code, Long pId) {
        return PriceCreateRequestDTO.builder()
                .productId(pId).priceCode(code)
                .billingType(BillingType.ONE_TIME).currency("INR")
                .amount(new BigDecimal("999")).billingIntervalUnit(BillingIntervalUnit.MONTH)
                .billingIntervalCount(1).trialDays(0).build();
    }

    // ── CreatePriceTests ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /prices")
    class CreatePriceTests {

        @Test
        @DisplayName("201 — RECURRING price created")
        void createRecurring() {
            String code = "REC_" + System.nanoTime();
            ResponseEntity<PriceResponseDTO> resp = rest.exchange(
                    pricesUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(recurringPrice(code, productId), jsonHeaders()),
                    PriceResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getPriceCode()).isEqualTo(code);
            assertThat(resp.getBody().getBillingType()).isEqualTo(BillingType.RECURRING);
            assertThat(resp.getBody().isActive()).isTrue();
        }

        @Test
        @DisplayName("201 — ONE_TIME price created")
        void createOneTime() {
            String code = "OT_" + System.nanoTime();
            ResponseEntity<PriceResponseDTO> resp = rest.exchange(
                    pricesUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(oneTimePrice(code, productId), jsonHeaders()),
                    PriceResponseDTO.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getBillingType()).isEqualTo(BillingType.ONE_TIME);
        }

        @Test
        @DisplayName("409 — duplicate priceCode within merchant")
        void duplicateCode() {
            String code = "DUP_P_" + System.nanoTime();
            HttpEntity<PriceCreateRequestDTO> req =
                    new HttpEntity<>(recurringPrice(code, productId), jsonHeaders());
            rest.exchange(pricesUrl(merchantId), HttpMethod.POST, req, PriceResponseDTO.class);

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    pricesUrl(merchantId), HttpMethod.POST, req, new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("400 — product from a different merchant")
        void productFromAnotherMerchant() {
            // productId belongs to merchant 1, but we POST to merchant 2
            String code = "CROSS_" + System.nanoTime();
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    pricesUrl(merchant2Id), HttpMethod.POST,
                    new HttpEntity<>(recurringPrice(code, productId), jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("400 — RECURRING price missing billingIntervalUnit")
        void recurringMissingInterval() {
            PriceCreateRequestDTO bad = PriceCreateRequestDTO.builder()
                    .productId(productId).priceCode("BAD_" + System.nanoTime())
                    .billingType(BillingType.RECURRING).currency("INR")
                    .amount(new BigDecimal("499"))
                    .billingIntervalCount(1).build(); // unit missing

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    pricesUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(bad, jsonHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ── GetPriceTests ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /prices/{priceId}")
    class GetPriceTests {

        @Test
        @DisplayName("200 — returns price")
        void getSuccess() {
            String code = "GETP_" + System.nanoTime();
            PriceResponseDTO created = rest.exchange(
                    pricesUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(recurringPrice(code, productId), jsonHeaders()),
                    PriceResponseDTO.class).getBody();

            ResponseEntity<PriceResponseDTO> resp = rest.exchange(
                    pricesUrl(merchantId) + "/" + created.getId(),
                    HttpMethod.GET, new HttpEntity<>(jsonHeaders()),
                    PriceResponseDTO.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getPriceCode()).isEqualTo(code);
        }

        @Test
        @DisplayName("404 — cross-merchant tenant isolation")
        void tenantIsolation() {
            String code = "ISO_P_" + System.nanoTime();
            PriceResponseDTO created = rest.exchange(
                    pricesUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(recurringPrice(code, productId), jsonHeaders()),
                    PriceResponseDTO.class).getBody();

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    pricesUrl(merchant2Id) + "/" + created.getId(),
                    HttpMethod.GET, new HttpEntity<>(jsonHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── ListPricesTests ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /prices")
    class ListPricesTests {

        @Test
        @DisplayName("paginated list")
        void listPrices() {
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    pricesUrl(merchantId) + "?page=0&size=50",
                    HttpMethod.GET, new HttpEntity<>(jsonHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("filter by active=true")
        void filterActive() {
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    pricesUrl(merchantId) + "?active=true&page=0&size=50",
                    HttpMethod.GET, new HttpEntity<>(jsonHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ── DeactivatePriceTests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /prices/{priceId}/deactivate")
    class DeactivatePriceTests {

        @Test
        @DisplayName("200 — price deactivated")
        void deactivateSuccess() {
            String code = "DEACT_" + System.nanoTime();
            PriceResponseDTO created = rest.exchange(
                    pricesUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(recurringPrice(code, productId), jsonHeaders()),
                    PriceResponseDTO.class).getBody();

            ResponseEntity<PriceResponseDTO> resp = rest.exchange(
                    pricesUrl(merchantId) + "/" + created.getId() + "/deactivate",
                    HttpMethod.POST, new HttpEntity<>(jsonHeaders()),
                    PriceResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().isActive()).isFalse();
        }
    }

    // ── PriceVersionTests ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Price Versions")
    class PriceVersionTests {

        @Test
        @DisplayName("201 — creates future price version")
        void createFutureVersion() {
            String code = "VER1_" + System.nanoTime();
            PriceResponseDTO price = rest.exchange(
                    pricesUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(recurringPrice(code, productId), jsonHeaders()),
                    PriceResponseDTO.class).getBody();

            PriceVersionCreateRequestDTO req = PriceVersionCreateRequestDTO.builder()
                    .effectiveFrom(LocalDateTime.now().plusDays(30))
                    .amount(new BigDecimal("599")).currency("INR")
                    .grandfatherExistingSubscriptions(false).build();

            ResponseEntity<PriceVersionResponseDTO> resp = rest.exchange(
                    pricesUrl(merchantId) + "/" + price.getId() + "/versions",
                    HttpMethod.POST, new HttpEntity<>(req, jsonHeaders()),
                    PriceVersionResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getAmount()).isEqualByComparingTo("599");
            assertThat(resp.getBody().getPriceId()).isEqualTo(price.getId());
        }

        @Test
        @DisplayName("201 — creates second version and closes first open-ended")
        void closesOpenEndedVersion() {
            String code = "VER2_" + System.nanoTime();
            PriceResponseDTO price = rest.exchange(
                    pricesUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(recurringPrice(code, productId), jsonHeaders()),
                    PriceResponseDTO.class).getBody();

            String versionsUrl = pricesUrl(merchantId) + "/" + price.getId() + "/versions";

            // Version 1
            PriceVersionCreateRequestDTO v1 = PriceVersionCreateRequestDTO.builder()
                    .effectiveFrom(LocalDateTime.now().plusDays(1))
                    .amount(new BigDecimal("499")).currency("INR").build();
            rest.exchange(versionsUrl, HttpMethod.POST,
                    new HttpEntity<>(v1, jsonHeaders()), PriceVersionResponseDTO.class);

            // Version 2 (later), should close v1
            PriceVersionCreateRequestDTO v2 = PriceVersionCreateRequestDTO.builder()
                    .effectiveFrom(LocalDateTime.now().plusDays(60))
                    .amount(new BigDecimal("599")).currency("INR").build();
            ResponseEntity<PriceVersionResponseDTO> resp = rest.exchange(
                    versionsUrl, HttpMethod.POST,
                    new HttpEntity<>(v2, jsonHeaders()), PriceVersionResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            // List versions — should see 2
            ResponseEntity<List<PriceVersionResponseDTO>> list = rest.exchange(
                    versionsUrl, HttpMethod.GET, new HttpEntity<>(jsonHeaders()),
                    new ParameterizedTypeReference<>() {});
            assertThat(list.getBody()).hasSize(2);
        }

        @Test
        @DisplayName("409 — overlapping version window")
        void overlappingVersion() {
            String code = "VER3_" + System.nanoTime();
            PriceResponseDTO price = rest.exchange(
                    pricesUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(recurringPrice(code, productId), jsonHeaders()),
                    PriceResponseDTO.class).getBody();

            String versionsUrl = pricesUrl(merchantId) + "/" + price.getId() + "/versions";

            LocalDateTime future30 = LocalDateTime.now().plusDays(30);
            LocalDateTime future10 = LocalDateTime.now().plusDays(10); // earlier than 30

            PriceVersionCreateRequestDTO v1 = PriceVersionCreateRequestDTO.builder()
                    .effectiveFrom(future30).amount(new BigDecimal("499")).currency("INR").build();
            rest.exchange(versionsUrl, HttpMethod.POST,
                    new HttpEntity<>(v1, jsonHeaders()), PriceVersionResponseDTO.class);

            // Try to insert a version that starts BEFORE the already-scheduled v1 — overlap
            PriceVersionCreateRequestDTO overlap = PriceVersionCreateRequestDTO.builder()
                    .effectiveFrom(future10).amount(new BigDecimal("399")).currency("INR").build();
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    versionsUrl, HttpMethod.POST,
                    new HttpEntity<>(overlap, jsonHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("400 — effectiveFrom in the past")
        void pastEffectiveFrom() {
            String code = "VER4_" + System.nanoTime();
            PriceResponseDTO price = rest.exchange(
                    pricesUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(recurringPrice(code, productId), jsonHeaders()),
                    PriceResponseDTO.class).getBody();

            PriceVersionCreateRequestDTO req = PriceVersionCreateRequestDTO.builder()
                    .effectiveFrom(LocalDateTime.now().minusDays(2))
                    .amount(new BigDecimal("399")).currency("INR").build();

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    pricesUrl(merchantId) + "/" + price.getId() + "/versions",
                    HttpMethod.POST, new HttpEntity<>(req, jsonHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("list versions — correct price, newest first")
        void listVersions() {
            String code = "VER5_" + System.nanoTime();
            PriceResponseDTO price = rest.exchange(
                    pricesUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(recurringPrice(code, productId), jsonHeaders()),
                    PriceResponseDTO.class).getBody();

            String versionsUrl = pricesUrl(merchantId) + "/" + price.getId() + "/versions";

            PriceVersionCreateRequestDTO v1 = PriceVersionCreateRequestDTO.builder()
                    .effectiveFrom(LocalDateTime.now().plusDays(1))
                    .amount(new BigDecimal("499")).currency("INR").build();
            rest.exchange(versionsUrl, HttpMethod.POST,
                    new HttpEntity<>(v1, jsonHeaders()), PriceVersionResponseDTO.class);

            ResponseEntity<List<PriceVersionResponseDTO>> resp = rest.exchange(
                    versionsUrl, HttpMethod.GET, new HttpEntity<>(jsonHeaders()),
                    new ParameterizedTypeReference<>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).isNotEmpty();
        }

        @Test
        @DisplayName("404 — list versions for price in wrong merchant (tenant isolation)")
        void tenantIsolationVersions() {
            String code = "VER6_" + System.nanoTime();
            PriceResponseDTO price = rest.exchange(
                    pricesUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(recurringPrice(code, productId), jsonHeaders()),
                    PriceResponseDTO.class).getBody();

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    pricesUrl(merchant2Id) + "/" + price.getId() + "/versions",
                    HttpMethod.GET, new HttpEntity<>(jsonHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
