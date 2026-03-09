package com.firstclub.risk;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.risk.controller.RiskDecisionAdminController}.
 */
@DisplayName("RiskDecisionAdminController — Integration Tests")
class RiskDecisionAdminControllerTest extends PostgresIntegrationTestBase {

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

    // ── GET / ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET / — 200 with empty page when no decisions exist")
    void listDecisions_empty_returns200() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/risk/decisions",
                HttpMethod.GET, new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("content");
    }

    @Test
    @DisplayName("GET /?merchantId=X — 200 filtered by merchant (empty)")
    void listDecisions_withMerchantFilter_returns200() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/risk/decisions?merchantId=" + merchantId,
                HttpMethod.GET, new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("content");
    }

    @Test
    @DisplayName("GET / — 401 without auth")
    void listDecisions_requiresAuth() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/risk/decisions",
                HttpMethod.GET, new HttpEntity<>(json()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
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
                .merchantCode("RD_" + System.nanoTime())
                .legalName("Risk Decision Test Merchant")
                .displayName("RD Test")
                .supportEmail("rd@test.com")
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
}
