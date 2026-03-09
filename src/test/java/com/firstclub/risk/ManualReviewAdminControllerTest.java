package com.firstclub.risk;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.risk.dto.ManualReviewResolveRequestDTO;
import com.firstclub.risk.entity.ReviewCaseStatus;
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
 * Integration tests for {@link com.firstclub.risk.controller.ManualReviewAdminController}.
 */
@DisplayName("ManualReviewAdminController — Integration Tests")
class ManualReviewAdminControllerTest extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = login();
    }

    // ── GET / ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET / — 200 with empty page when no review cases exist")
    void listCases_empty_returns200() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/risk/review-cases",
                HttpMethod.GET, new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("content");
    }

    @Test
    @DisplayName("GET /?status=OPEN — 200 filtered by status")
    void listCases_statusFilter_returns200() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/risk/review-cases?status=OPEN",
                HttpMethod.GET, new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET / — 401 without auth")
    void listCases_requiresAuth() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/risk/review-cases",
                HttpMethod.GET, new HttpEntity<>(json()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    // ── POST /{id}/resolve — non-existent ID ──────────────────────────────────

    @Test
    @DisplayName("POST /{id}/resolve — 404 for non-existent case ID")
    void resolveCase_notFound_returns404() {
        ManualReviewResolveRequestDTO req = ManualReviewResolveRequestDTO.builder()
                .resolution(ReviewCaseStatus.APPROVED).build();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/risk/review-cases/999999/resolve",
                HttpMethod.POST, new HttpEntity<>(req, auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("POST /{id}/resolve — 401 without auth")
    void resolveCase_requiresAuth() {
        ManualReviewResolveRequestDTO req = ManualReviewResolveRequestDTO.builder()
                .resolution(ReviewCaseStatus.APPROVED).build();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/risk/review-cases/1/resolve",
                HttpMethod.POST, new HttpEntity<>(req, json()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    // ── POST /{id}/assign — non-existent ID ───────────────────────────────────

    @Test
    @DisplayName("POST /{id}/assign — 404 for non-existent case ID")
    void assignCase_notFound_returns404() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/risk/review-cases/999999/assign?userId=1",
                HttpMethod.POST, new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
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
}
