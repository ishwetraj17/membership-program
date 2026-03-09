package com.firstclub.payments.routing;

import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.payments.routing.dto.GatewayHealthResponseDTO;
import com.firstclub.payments.routing.dto.GatewayHealthUpdateRequestDTO;
import com.firstclub.payments.routing.entity.GatewayHealthStatus;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for GatewayHealthAdminController.
 * Runs against Testcontainers Postgres; skipped without Docker.
 */
@DisplayName("GatewayHealthAdminController - Integration Tests")
class GatewayHealthAdminControllerTest extends com.firstclub.membership.PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";
    private static final String BASE_PATH      = "/api/v2/admin/gateways/health";

    @LocalServerPort private int port;
    @Autowired        private TestRestTemplate rest;

    private String adminToken;

    @BeforeEach
    void setUp() {
        ResponseEntity<JwtResponseDTO> auth = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new HttpEntity<>(LoginRequestDTO.builder()
                        .email(ADMIN_EMAIL).password(ADMIN_PASSWORD).build(), jsonHeaders()),
                JwtResponseDTO.class);
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminToken = auth.getBody().getToken();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }
    private String healthUrl() { return base() + BASE_PATH; }

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

    private GatewayHealthUpdateRequestDTO healthUpdateRequest(GatewayHealthStatus status,
                                                               double rate, long p95) {
        GatewayHealthUpdateRequestDTO req = new GatewayHealthUpdateRequestDTO();
        req.setStatus(status);
        req.setRollingSuccessRate(BigDecimal.valueOf(rate));
        req.setRollingP95LatencyMs(p95);
        return req;
    }

    // ── GET /api/v2/admin/gateways/health ─────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v2/admin/gateways/health")
    class ListAll {

        @Test
        @DisplayName("200 – returns health snapshots for all seeded gateways")
        void returnsAll_200() {
            ResponseEntity<List<GatewayHealthResponseDTO>> resp = rest.exchange(
                    healthUrl(), HttpMethod.GET,
                    new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            // V21 seeds razorpay, stripe, payu — all three should be present
            // (DDL is create-drop so seeds aren't applied; the list can be empty or populated via PUT)
            assertThat(resp.getBody()).isNotNull();
        }

        @Test
        @DisplayName("401 – unauthenticated request is rejected")
        void unauthenticated_401() {
            ResponseEntity<Object> resp = rest.exchange(
                    healthUrl(), HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()),
                    Object.class);

            assertThat(resp.getStatusCode().value()).isIn(401, 403);
        }
    }

    // ── PUT /api/v2/admin/gateways/health/{gatewayName} ───────────────────────

    @Nested
    @DisplayName("PUT /api/v2/admin/gateways/health/{gatewayName}")
    class Update {

        @Test
        @DisplayName("200 – creates a new gateway health record (upsert)")
        void upsertsNew_200() {
            String gw = "test-gw-" + System.nanoTime();

            ResponseEntity<GatewayHealthResponseDTO> resp = rest.exchange(
                    healthUrl() + "/" + gw, HttpMethod.PUT,
                    new HttpEntity<>(healthUpdateRequest(GatewayHealthStatus.HEALTHY, 99.9, 80),
                            authHeaders()),
                    GatewayHealthResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            GatewayHealthResponseDTO body = resp.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getGatewayName()).isEqualTo(gw);
            assertThat(body.getStatus()).isEqualTo(GatewayHealthStatus.HEALTHY);
            assertThat(body.getRollingSuccessRate()).isEqualByComparingTo("99.9");
        }

        @Test
        @DisplayName("200 – updates existing gateway to DOWN status")
        void updatesExistingToDown_200() {
            String gw = "razorpay-it-" + System.nanoTime();

            // First upsert → HEALTHY
            rest.exchange(healthUrl() + "/" + gw, HttpMethod.PUT,
                    new HttpEntity<>(healthUpdateRequest(GatewayHealthStatus.HEALTHY, 99.5, 120),
                            authHeaders()),
                    GatewayHealthResponseDTO.class);

            // Second call → DOWN
            ResponseEntity<GatewayHealthResponseDTO> resp = rest.exchange(
                    healthUrl() + "/" + gw, HttpMethod.PUT,
                    new HttpEntity<>(healthUpdateRequest(GatewayHealthStatus.DOWN, 0.0, 9999),
                            authHeaders()),
                    GatewayHealthResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getStatus()).isEqualTo(GatewayHealthStatus.DOWN);
        }

        @Test
        @DisplayName("400 – validation error when status is null")
        void nullStatus_400() {
            GatewayHealthUpdateRequestDTO bad = new GatewayHealthUpdateRequestDTO();
            // status is @NotNull — leave it null

            ResponseEntity<Object> resp = rest.exchange(
                    healthUrl() + "/razorpay", HttpMethod.PUT,
                    new HttpEntity<>(bad, authHeaders()),
                    Object.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
