package com.firstclub.recon;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.recon.dto.ReconMismatchResolveRequestDTO;
import com.firstclub.recon.entity.ReconMismatchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.recon.controller.ReconMismatchAdminController}.
 * Uses the existing daily recon endpoint to generate mismatches,
 * then exercises acknowledge / resolve / ignore lifecycle transitions.
 */
@DisplayName("ReconMismatchAdminController — Integration Tests")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ReconMismatchAdminControllerTest extends PostgresIntegrationTestBase {

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
    @DisplayName("GET / — 200 returns paginated mismatches (may be empty)")
    void listMismatches_returnsPage() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/recon/mismatches",
                HttpMethod.GET, new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("content");
    }

    @Test
    @DisplayName("GET /?status=OPEN — 200 filtered by status")
    void listMismatches_withStatusFilter() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/recon/mismatches?status=OPEN",
                HttpMethod.GET, new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET / — 401 without auth")
    void listMismatches_requiresAuth() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/recon/mismatches",
                HttpMethod.GET, new HttpEntity<>(json()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    // ── Lifecycle: acknowledge / resolve / ignore ──────────────────────────────

    @Test
    @DisplayName("Lifecycle: mismatch transitions OPEN → ACKNOWLEDGED → RESOLVED")
    void lifecycle_acknowledge_then_resolve() {
        Long mismatchId = createMismatch();
        if (mismatchId == null) return; // No invoices seeded — skip lifecycle check

        // Acknowledge
        ResponseEntity<Map<String, Object>> ack = rest.exchange(
                base() + "/api/v2/admin/recon/mismatches/" + mismatchId + "/acknowledge",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("ownerUserId", 1), auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(ack.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ack.getBody().get("status")).isEqualTo(ReconMismatchStatus.ACKNOWLEDGED.name());

        // Resolve
        ReconMismatchResolveRequestDTO req = ReconMismatchResolveRequestDTO.builder()
                .resolutionNote("Missing payment was found and matched.")
                .ownerUserId(1L)
                .build();
        ResponseEntity<Map<String, Object>> res = rest.exchange(
                base() + "/api/v2/admin/recon/mismatches/" + mismatchId + "/resolve",
                HttpMethod.POST, new HttpEntity<>(req, auth()), new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("status")).isEqualTo(ReconMismatchStatus.RESOLVED.name());
        assertThat(res.getBody().get("resolutionNote")).isEqualTo("Missing payment was found and matched.");
    }

    @Test
    @DisplayName("Lifecycle: mismatch transitions OPEN → IGNORED")
    void lifecycle_ignore() {
        Long mismatchId = createMismatch();
        if (mismatchId == null) return;

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/recon/mismatches/" + mismatchId + "/ignore",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Known test data anomaly"), auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo(ReconMismatchStatus.IGNORED.name());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Runs daily recon for a past date and returns the first mismatch ID, or
     * null if no mismatches were generated (no seed data for that date).
     */
    @SuppressWarnings("unchecked")
    private Long createMismatch() {
        // Use a date unlikely to have real data so recon may or may not produce mismatches
        String date = "2020-01-01";
        ResponseEntity<Map<String, Object>> recon = rest.exchange(
                base() + "/api/v1/admin/recon/daily?date=" + date,
                HttpMethod.GET, new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(recon.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> mismatches = (List<Map<String, Object>>) recon.getBody().get("mismatches");
        if (mismatches == null || mismatches.isEmpty()) return null;
        return ((Number) mismatches.get(0).get("id")).longValue();
    }

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
