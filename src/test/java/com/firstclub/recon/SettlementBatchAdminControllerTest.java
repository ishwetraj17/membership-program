package com.firstclub.recon;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.merchant.dto.MerchantCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.recon.controller.SettlementBatchAdminController}.
 */
@DisplayName("SettlementBatchAdminController — Integration Tests")
class SettlementBatchAdminControllerTest extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    private String adminToken;
    private Long   merchantId;

    @BeforeEach
    void setUp() {
        adminToken = login();
        merchantId = createMerchant();
    }

    // ── POST /run ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /run — 200 for a date with no captured payments (empty batch)")
    void runBatch_noCapturedPayments_returnsPostedBatch() {
        String url = base() + "/api/v2/admin/settlement-batches/run"
                + "?merchantId=" + merchantId + "&date=2024-01-01&gatewayName=STRIPE";

        ResponseEntity<Map<String, Object>> resp = rest.exchange(url, HttpMethod.POST,
                new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("id");
        assertThat(resp.getBody().get("status")).isEqualTo("POSTED");
    }

    @Test
    @DisplayName("POST /run — 401 without auth")
    void runBatch_requiresAuth() {
        String url = base() + "/api/v2/admin/settlement-batches/run"
                + "?merchantId=1&date=2024-01-01&gatewayName=STRIPE";

        ResponseEntity<Map<String, Object>> resp = rest.exchange(url, HttpMethod.POST,
                new HttpEntity<>(json()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    // ── GET /{batchId} ────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /{batchId} — 200 for existing batch")
    void getBatch_exists_returns200() {
        // Create a batch first
        Long batchId = createBatch();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/settlement-batches/" + batchId,
                HttpMethod.GET, new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("id")).isEqualTo(batchId.intValue());
    }

    // ── GET /{batchId}/items ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /{batchId}/items — 200 with empty list for batch with no payments")
    void getBatchItems_emptyBatch_returnsEmptyList() {
        Long batchId = createBatch();

        ResponseEntity<Object[]> resp = rest.exchange(
                base() + "/api/v2/admin/settlement-batches/" + batchId + "/items",
                HttpMethod.GET, new HttpEntity<>(auth()), Object[].class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull().isEmpty();
    }

    // ── GET / ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET / — 200 with pagination for merchant")
    void listBatches_returnsPaginatedResults() {
        createBatch();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/settlement-batches?merchantId=" + merchantId,
                HttpMethod.GET, new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("content");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders auth() {
        HttpHeaders h = json();
        h.setBearerAuth(adminToken);
        return h;
    }

    private String login() {
        ResponseEntity<JwtResponseDTO> resp = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new HttpEntity<>(LoginRequestDTO.builder()
                        .email(ADMIN_EMAIL).password(ADMIN_PASSWORD).build(), json()),
                JwtResponseDTO.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().getToken();
    }

    private Long createMerchant() {
        MerchantCreateRequestDTO req = MerchantCreateRequestDTO.builder()
                .merchantCode("SB_" + System.nanoTime())
                .legalName("Settlement Test Merchant")
                .displayName("Settlement Batch Test")
                .supportEmail("sb@test.com")
                .defaultCurrency("INR")
                .countryCode("IN")
                .timezone("Asia/Kolkata")
                .build();
        ResponseEntity<MerchantResponseDTO> resp = rest.exchange(
                base() + "/api/v2/admin/merchants",
                HttpMethod.POST, new HttpEntity<>(req, auth()), MerchantResponseDTO.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody().getId();
    }

    private Long createBatch() {
        String url = base() + "/api/v2/admin/settlement-batches/run"
                + "?merchantId=" + merchantId + "&date=" + LocalDate.now().minusDays(7) + "&gatewayName=STRIPE";
        ResponseEntity<Map<String, Object>> resp = rest.exchange(url, HttpMethod.POST,
                new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) resp.getBody().get("id")).longValue();
    }
}
