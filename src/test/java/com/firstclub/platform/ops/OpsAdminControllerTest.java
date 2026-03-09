package com.firstclub.platform.ops;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.platform.ops.dto.FeatureFlagUpdateRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.platform.ops.controller.OpsAdminController}.
 * Covers the six system-ops endpoints end-to-end against a real PostgreSQL container.
 */
@DisplayName("OpsAdminController — Integration Tests")
class OpsAdminControllerTest extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = login();
    }

    // ── GET /health/deep ─────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /health/deep — 200 with HEALTHY status and counters")
    void deepHealth_200_returnsHealthyReport() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/system/health/deep",
                HttpMethod.GET,
                new HttpEntity<>(auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("overallStatus");
        assertThat(resp.getBody()).containsKey("dbReachable");
        assertThat(resp.getBody()).containsKey("dlqCount");
        assertThat(resp.getBody()).containsKey("outboxPendingCount");
        assertThat(resp.getBody()).containsKey("checkedAt");
        assertThat(resp.getBody().get("dbReachable")).isEqualTo(true);
    }

    @Test
    @DisplayName("GET /health/deep — 401 without auth")
    void deepHealth_401_withoutAuth() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/system/health/deep",
                HttpMethod.GET,
                new HttpEntity<>(json()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    // ── GET /dlq ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /dlq — 200 with empty list when no DLQ entries exist")
    void listDlq_200_emptyInitially() {
        ResponseEntity<List> resp = rest.exchange(
                base() + "/api/v2/admin/system/dlq",
                HttpMethod.GET,
                new HttpEntity<>(auth()),
                List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    @DisplayName("POST /dlq/{id}/retry — 404 for non-existent DLQ entry")
    void retryDlq_404_whenNotFound() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/system/dlq/999999/retry",
                HttpMethod.POST,
                new HttpEntity<>(auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── GET /outbox/lag ──────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /outbox/lag — 200 with lag summary")
    void outboxLag_200_returnsSummary() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/system/outbox/lag",
                HttpMethod.GET,
                new HttpEntity<>(auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("newCount");
        assertThat(resp.getBody()).containsKey("failedCount");
        assertThat(resp.getBody()).containsKey("totalPending");
        assertThat(resp.getBody()).containsKey("byEventType");
        assertThat(resp.getBody()).containsKey("reportedAt");
    }

    // ── GET /feature-flags ───────────────────────────────────────────────────

    @Test
    @DisplayName("GET /feature-flags — 200 with (potentially empty) list")
    void listFeatureFlags_200() {
        ResponseEntity<List> resp = rest.exchange(
                base() + "/api/v2/admin/system/feature-flags",
                HttpMethod.GET,
                new HttpEntity<>(auth()),
                List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
    }

    // ── PUT /feature-flags/{flagKey} ─────────────────────────────────────────

    @Test
    @DisplayName("PUT /feature-flags/{flagKey} — 200 creates flag when it does not exist")
    void updateFeatureFlag_200_createsFlagOnFirstCall() {
        FeatureFlagUpdateRequestDTO req = FeatureFlagUpdateRequestDTO.builder()
                .enabled(true)
                .configJson("{\"rollout\":100}")
                .build();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/system/feature-flags/GATEWAY_ROUTING",
                HttpMethod.PUT,
                new HttpEntity<>(req, auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("flagKey")).isEqualTo("GATEWAY_ROUTING");
        assertThat(resp.getBody().get("enabled")).isEqualTo(true);
        assertThat(resp.getBody().get("scope")).isEqualTo("GLOBAL");
    }

    @Test
    @DisplayName("PUT /feature-flags/{flagKey} — 200 can disable a previously enabled flag")
    void updateFeatureFlag_200_disablesFlag() {
        // First enable
        rest.exchange(
                base() + "/api/v2/admin/system/feature-flags/WEBHOOKS_ENABLED",
                HttpMethod.PUT,
                new HttpEntity<>(FeatureFlagUpdateRequestDTO.builder().enabled(true).build(), auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        // Then disable
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/system/feature-flags/WEBHOOKS_ENABLED",
                HttpMethod.PUT,
                new HttpEntity<>(FeatureFlagUpdateRequestDTO.builder().enabled(false).build(), auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("enabled")).isEqualTo(false);
    }

    @Test
    @DisplayName("PUT /feature-flags/{flagKey} — 400 when enabled field is missing")
    void updateFeatureFlag_400_whenEnabledMissing() {
        // Send empty body — @NotNull on enabled should trigger validation error
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/system/feature-flags/SOME_FLAG",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(), auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("PUT /feature-flags — 401 without auth")
    void updateFeatureFlag_401_withoutAuth() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/system/feature-flags/SOME_FLAG",
                HttpMethod.PUT,
                new HttpEntity<>(FeatureFlagUpdateRequestDTO.builder().enabled(true).build(), json()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    // ── Phase 20 — System Summary & Scaling Readiness ────────────────────────

    @Test
    @DisplayName("GET /summary — 200 with operational counters")
    void systemSummary_200() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/system/summary",
                HttpMethod.GET,
                new HttpEntity<>(auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKeys(
                "outboxPendingCount", "outboxFailedCount", "dlqCount",
                "webhookPendingCount", "webhookFailedCount",
                "dunningBacklogCount", "staleJobLockCount",
                "integrityViolationCount", "integrityLastRunStatus", "generatedAt");
    }

    @Test
    @DisplayName("GET /scaling-readiness — 200 with architecture shape and evolution stages")
    void scalingReadiness_200() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/system/scaling-readiness",
                HttpMethod.GET,
                new HttpEntity<>(auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("architectureShape")).isEqualTo("MODULAR_MONOLITH");
        assertThat(resp.getBody()).containsKeys(
                "currentBottlenecks", "projectionBackedSubsystems",
                "redisBackedSubsystems", "singleNodeRisks",
                "decompositionCandidates", "evolutionStages", "generatedAt");
        @SuppressWarnings("unchecked")
        var stages = (java.util.Map<String, String>) resp.getBody().get("evolutionStages");
        assertThat(stages).containsKeys("stage_1", "stage_2", "stage_3", "stage_4", "stage_5", "stage_6");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
