package com.firstclub.merchant;

import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.merchant.dto.MerchantCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.merchant.dto.MerchantStatusUpdateRequestDTO;
import com.firstclub.merchant.dto.MerchantUpdateRequestDTO;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.service.MerchantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for MerchantAdminController.
 *
 * Verifies merchant CRUD, status transitions, pagination, and auth enforcement
 * via the live HTTP stack against a Testcontainers Postgres database.
 *
 * Implemented by Shwet Raj
 */
@DisplayName("Merchant Admin Controller - Integration Tests")
class MerchantAdminControllerTest extends com.firstclub.membership.PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @Autowired
    private MerchantService merchantService;

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    private String adminToken;

    private String getBaseUrl() {
        return "http://localhost:" + port;
    }

    private String merchantsUrl() {
        return getBaseUrl() + "/api/v2/admin/merchants";
    }

    @BeforeEach
    void obtainAdminToken() {
        LoginRequestDTO login = LoginRequestDTO.builder()
                .email(ADMIN_EMAIL)
                .password(ADMIN_PASSWORD)
                .build();

        ResponseEntity<JwtResponseDTO> auth = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/auth/login",
                new HttpEntity<>(login, jsonHeaders()),
                JwtResponseDTO.class
        );
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminToken = auth.getBody().getToken();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(adminToken);
        return headers;
    }

    private <T> HttpEntity<T> authed(T body) {
        return new HttpEntity<>(body, authHeaders());
    }

    private HttpEntity<Void> authedGet() {
        return new HttpEntity<>(authHeaders());
    }

    private MerchantCreateRequestDTO uniqueCreateRequest(String codePrefix) {
        return MerchantCreateRequestDTO.builder()
                .merchantCode(codePrefix + "_" + System.currentTimeMillis())
                .legalName("Test Legal Name")
                .displayName("Test Display")
                .supportEmail("support@test.com")
                .defaultCurrency("INR")
                .countryCode("IN")
                .timezone("Asia/Kolkata")
                .build();
    }

    // ========================================
    // CREATE MERCHANT
    // ========================================

    @Nested
    @DisplayName("POST /api/v2/admin/merchants")
    class CreateMerchantTests {

        @Test
        @DisplayName("Should create merchant and return 201")
        void shouldCreateMerchantAndReturn201() {
            MerchantCreateRequestDTO request = uniqueCreateRequest("INTG");

            ResponseEntity<MerchantResponseDTO> response = restTemplate.postForEntity(
                    merchantsUrl(), authed(request), MerchantResponseDTO.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getId()).isNotNull();
            assertThat(response.getBody().getMerchantCode()).isEqualTo(request.getMerchantCode());
            assertThat(response.getBody().getStatus()).isEqualTo(MerchantStatus.PENDING);
        }

        @Test
        @DisplayName("Should return 409 for duplicate merchant code")
        void shouldReturn409ForDuplicateCode() {
            MerchantCreateRequestDTO request = uniqueCreateRequest("DUP");

            // First creation succeeds
            restTemplate.postForEntity(merchantsUrl(), authed(request), MerchantResponseDTO.class);

            // Second with same code must conflict
            ResponseEntity<Object> second = restTemplate.postForEntity(
                    merchantsUrl(), authed(request), Object.class
            );
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("Should return 400 for invalid merchant code pattern")
        void shouldReturn400ForInvalidCode() {
            MerchantCreateRequestDTO request = uniqueCreateRequest("invalid-lowercase");
            request.setMerchantCode("invalid lowercase");  // spaces not allowed

            ResponseEntity<Object> response = restTemplate.postForEntity(
                    merchantsUrl(), authed(request), Object.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Should return 401 without token")
        void shouldReturn401WithoutToken() {
            MerchantCreateRequestDTO request = uniqueCreateRequest("NOAUTH");
            HttpEntity<MerchantCreateRequestDTO> unauthenticated = new HttpEntity<>(request, jsonHeaders());

            ResponseEntity<Object> response = restTemplate.postForEntity(
                    merchantsUrl(), unauthenticated, Object.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ========================================
    // GET MERCHANT BY ID
    // ========================================

    @Nested
    @DisplayName("GET /api/v2/admin/merchants/{id}")
    class GetMerchantByIdTests {

        @Test
        @DisplayName("Should return merchant by ID")
        void shouldReturnMerchantById() {
            MerchantCreateRequestDTO createReq = uniqueCreateRequest("GETBYID");
            ResponseEntity<MerchantResponseDTO> created = restTemplate.postForEntity(
                    merchantsUrl(), authed(createReq), MerchantResponseDTO.class
            );
            Long id = created.getBody().getId();

            ResponseEntity<MerchantResponseDTO> response = restTemplate.exchange(
                    merchantsUrl() + "/" + id, HttpMethod.GET, authedGet(), MerchantResponseDTO.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getId()).isEqualTo(id);
            assertThat(response.getBody().getMerchantCode()).isEqualTo(createReq.getMerchantCode());
        }

        @Test
        @DisplayName("Should return 404 for non-existent merchant")
        void shouldReturn404ForNonExistentMerchant() {
            ResponseEntity<Object> response = restTemplate.exchange(
                    merchantsUrl() + "/99999999", HttpMethod.GET, authedGet(), Object.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ========================================
    // UPDATE MERCHANT
    // ========================================

    @Nested
    @DisplayName("PUT /api/v2/admin/merchants/{id}")
    class UpdateMerchantTests {

        @Test
        @DisplayName("Should update merchant display name")
        void shouldUpdateMerchantDisplayName() {
            MerchantCreateRequestDTO createReq = uniqueCreateRequest("UPD");
            ResponseEntity<MerchantResponseDTO> created = restTemplate.postForEntity(
                    merchantsUrl(), authed(createReq), MerchantResponseDTO.class
            );
            Long id = created.getBody().getId();

            MerchantUpdateRequestDTO updateReq = new MerchantUpdateRequestDTO();
            updateReq.setDisplayName("Updated Display Name");

            ResponseEntity<MerchantResponseDTO> response = restTemplate.exchange(
                    merchantsUrl() + "/" + id, HttpMethod.PUT, authed(updateReq), MerchantResponseDTO.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getDisplayName()).isEqualTo("Updated Display Name");
        }
    }

    // ========================================
    // UPDATE STATUS
    // ========================================

    @Nested
    @DisplayName("PUT /api/v2/admin/merchants/{id}/status")
    class UpdateStatusTests {

        @Test
        @DisplayName("Should transition PENDING → ACTIVE")
        void shouldTransitionPendingToActive() {
            MerchantCreateRequestDTO createReq = uniqueCreateRequest("ACTV");
            ResponseEntity<MerchantResponseDTO> created = restTemplate.postForEntity(
                    merchantsUrl(), authed(createReq), MerchantResponseDTO.class
            );
            Long id = created.getBody().getId();
            assertThat(created.getBody().getStatus()).isEqualTo(MerchantStatus.PENDING);

            MerchantStatusUpdateRequestDTO statusReq = new MerchantStatusUpdateRequestDTO();
            statusReq.setStatus(MerchantStatus.ACTIVE);

            ResponseEntity<MerchantResponseDTO> response = restTemplate.exchange(
                    merchantsUrl() + "/" + id + "/status",
                    HttpMethod.PUT, authed(statusReq), MerchantResponseDTO.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getStatus()).isEqualTo(MerchantStatus.ACTIVE);
        }

        @Test
        @DisplayName("Should return 400 for invalid status transition: PENDING → SUSPENDED")
        void shouldReturn400ForInvalidTransition() {
            MerchantCreateRequestDTO createReq = uniqueCreateRequest("INVTR");
            ResponseEntity<MerchantResponseDTO> created = restTemplate.postForEntity(
                    merchantsUrl(), authed(createReq), MerchantResponseDTO.class
            );
            Long id = created.getBody().getId();

            MerchantStatusUpdateRequestDTO statusReq = new MerchantStatusUpdateRequestDTO();
            statusReq.setStatus(MerchantStatus.SUSPENDED);

            ResponseEntity<Object> response = restTemplate.exchange(
                    merchantsUrl() + "/" + id + "/status",
                    HttpMethod.PUT, authed(statusReq), Object.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ========================================
    // LIST / PAGINATION
    // ========================================

    @Nested
    @DisplayName("GET /api/v2/admin/merchants (pagination)")
    class ListMerchantsTests {

        @Test
        @DisplayName("Should return paginated merchant list")
        void shouldReturnPaginatedList() {
            // Create at least one merchant so the list is non-trivial
            restTemplate.postForEntity(merchantsUrl(), authed(uniqueCreateRequest("LIST")),
                    MerchantResponseDTO.class);

            ResponseEntity<Object> response = restTemplate.exchange(
                    merchantsUrl() + "?page=0&size=10", HttpMethod.GET, authedGet(), Object.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
        }

        @Test
        @DisplayName("Should return 400 when page size exceeds max (100)")
        void shouldReturn400WhenSizeExceedsMax() {
            ResponseEntity<Object> response = restTemplate.exchange(
                    merchantsUrl() + "?page=0&size=200", HttpMethod.GET, authedGet(), Object.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
