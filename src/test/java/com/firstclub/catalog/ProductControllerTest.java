package com.firstclub.catalog;

import com.firstclub.catalog.dto.*;
import com.firstclub.catalog.entity.BillingIntervalUnit;
import com.firstclub.catalog.entity.BillingType;
import com.firstclub.catalog.entity.ProductStatus;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for ProductController.
 * Runs against Testcontainers Postgres; skipped without Docker.
 */
@DisplayName("Product Controller - Integration Tests")
class ProductControllerTest extends com.firstclub.membership.PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired MerchantService merchantService;

    private String adminToken;
    private Long merchantId;
    private Long merchant2Id;

    @BeforeEach
    void setUp() {
        // Authenticate
        ResponseEntity<JwtResponseDTO> auth = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new LoginRequestDTO(ADMIN_EMAIL, ADMIN_PASSWORD),
                JwtResponseDTO.class);
        adminToken = auth.getBody().getToken();

        // Create and activate merchant 1
        String code1 = "CAT_M1_" + System.nanoTime();
        MerchantResponseDTO m1 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder().merchantCode(code1)
                        .legalName("Catalog Tenant One").displayName("T1")
                        .supportEmail("ct1@test.com").defaultCurrency("INR")
                        .countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m1.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchantId = m1.getId();

        // Create and activate merchant 2 (for isolation tests)
        String code2 = "CAT_M2_" + System.nanoTime();
        MerchantResponseDTO m2 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder().merchantCode(code2)
                        .legalName("Catalog Tenant Two").displayName("T2")
                        .supportEmail("ct2@test.com").defaultCurrency("INR")
                        .countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m2.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchant2Id = m2.getId();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(adminToken);
        return h;
    }

    private String productsUrl(Long mId) {
        return base() + "/api/v2/merchants/" + mId + "/products";
    }

    private ProductCreateRequestDTO product(String code) {
        return new ProductCreateRequestDTO(code, "Product " + code, "Description");
    }

    // ── CreateProductTests ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /products")
    class CreateProductTests {

        @Test
        @DisplayName("201 — product created")
        void createSuccess() {
            String code = "PROD_" + System.nanoTime();
            ResponseEntity<ProductResponseDTO> resp = rest.exchange(
                    productsUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(product(code), jsonHeaders()),
                    ProductResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getProductCode()).isEqualTo(code);
            assertThat(resp.getBody().getStatus()).isEqualTo(ProductStatus.ACTIVE);
            assertThat(resp.getBody().getMerchantId()).isEqualTo(merchantId);
        }

        @Test
        @DisplayName("409 — duplicate productCode within merchant")
        void duplicateCode() {
            String code = "DUP_" + System.nanoTime();
            HttpEntity<ProductCreateRequestDTO> req =
                    new HttpEntity<>(product(code), jsonHeaders());
            rest.exchange(productsUrl(merchantId), HttpMethod.POST, req, ProductResponseDTO.class);

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    productsUrl(merchantId), HttpMethod.POST, req, new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("same code in different merchants → both succeed")
        void sameCodeDifferentMerchants() {
            String code = "SHARED_" + System.nanoTime();
            HttpEntity<ProductCreateRequestDTO> req1 =
                    new HttpEntity<>(product(code), jsonHeaders());
            HttpEntity<ProductCreateRequestDTO> req2 =
                    new HttpEntity<>(product(code), jsonHeaders());

            assertThat(rest.exchange(productsUrl(merchantId), HttpMethod.POST, req1,
                    ProductResponseDTO.class).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
            assertThat(rest.exchange(productsUrl(merchant2Id), HttpMethod.POST, req2,
                    ProductResponseDTO.class).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("400 — missing required fields")
        void validationError() {
            ProductCreateRequestDTO bad = new ProductCreateRequestDTO("", "", null);
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    productsUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(bad, jsonHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("401 — unauthenticated request")
        void unauthenticated() {
            HttpHeaders noAuth = new HttpHeaders();
            noAuth.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    productsUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(product("X"), noAuth), new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
    }

    // ── GetProductTests ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /products/{productId}")
    class GetProductTests {

        @Test
        @DisplayName("200 — returns product")
        void getSuccess() {
            String code = "GET_" + System.nanoTime();
            ProductResponseDTO created = rest.exchange(
                    productsUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(product(code), jsonHeaders()),
                    ProductResponseDTO.class).getBody();

            ResponseEntity<ProductResponseDTO> resp = rest.exchange(
                    productsUrl(merchantId) + "/" + created.getId(),
                    HttpMethod.GET, new HttpEntity<>(jsonHeaders()),
                    ProductResponseDTO.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getProductCode()).isEqualTo(code);
        }

        @Test
        @DisplayName("404 — cross-merchant isolation")
        void tenantIsolation() {
            String code = "ISO_" + System.nanoTime();
            ProductResponseDTO created = rest.exchange(
                    productsUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(product(code), jsonHeaders()),
                    ProductResponseDTO.class).getBody();

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    productsUrl(merchant2Id) + "/" + created.getId(),
                    HttpMethod.GET, new HttpEntity<>(jsonHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── ListProductsTests ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /products")
    class ListProductsTests {

        @Test
        @DisplayName("paginated list returns products for merchant")
        void listProducts() {
            String code = "LIST_" + System.nanoTime();
            rest.exchange(productsUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(product(code), jsonHeaders()), ProductResponseDTO.class);

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    productsUrl(merchantId) + "?page=0&size=50",
                    HttpMethod.GET, new HttpEntity<>(jsonHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("filter by status=ACTIVE")
        void filterByStatus() {
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    productsUrl(merchantId) + "?status=ACTIVE&page=0&size=50",
                    HttpMethod.GET, new HttpEntity<>(jsonHeaders()), new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ── UpdateProductTests ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /products/{productId}")
    class UpdateProductTests {

        @Test
        @DisplayName("200 — updates name")
        void updateSuccess() {
            String code = "UPD_" + System.nanoTime();
            ProductResponseDTO created = rest.exchange(
                    productsUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(product(code), jsonHeaders()),
                    ProductResponseDTO.class).getBody();

            ProductUpdateRequestDTO upd = new ProductUpdateRequestDTO("Updated Name", "New desc");
            ResponseEntity<ProductResponseDTO> resp = rest.exchange(
                    productsUrl(merchantId) + "/" + created.getId(),
                    HttpMethod.PUT, new HttpEntity<>(upd, jsonHeaders()),
                    ProductResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getName()).isEqualTo("Updated Name");
        }
    }

    // ── ArchiveProductTests ────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /products/{productId}/archive")
    class ArchiveProductTests {

        @Test
        @DisplayName("200 — product becomes ARCHIVED")
        void archiveSuccess() {
            String code = "ARC_" + System.nanoTime();
            ProductResponseDTO created = rest.exchange(
                    productsUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(product(code), jsonHeaders()),
                    ProductResponseDTO.class).getBody();

            ResponseEntity<ProductResponseDTO> resp = rest.exchange(
                    productsUrl(merchantId) + "/" + created.getId() + "/archive",
                    HttpMethod.POST, new HttpEntity<>(jsonHeaders()),
                    ProductResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getStatus()).isEqualTo(ProductStatus.ARCHIVED);
        }

        @Test
        @DisplayName("idempotent — archiving twice returns 200")
        void idempotentArchive() {
            String code = "ARC2_" + System.nanoTime();
            ProductResponseDTO created = rest.exchange(
                    productsUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(product(code), jsonHeaders()),
                    ProductResponseDTO.class).getBody();

            String archiveUrl = productsUrl(merchantId) + "/" + created.getId() + "/archive";
            rest.exchange(archiveUrl, HttpMethod.POST, new HttpEntity<>(jsonHeaders()), ProductResponseDTO.class);
            ResponseEntity<ProductResponseDTO> resp = rest.exchange(
                    archiveUrl, HttpMethod.POST, new HttpEntity<>(jsonHeaders()), ProductResponseDTO.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
