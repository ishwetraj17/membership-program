package com.firstclub.payments.routing;

import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.payments.routing.dto.GatewayRouteRuleCreateRequestDTO;
import com.firstclub.payments.routing.dto.GatewayRouteRuleResponseDTO;
import com.firstclub.payments.routing.dto.GatewayRouteRuleUpdateRequestDTO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for GatewayRouteAdminController.
 * Runs against Testcontainers Postgres; skipped without Docker.
 */
@DisplayName("GatewayRouteAdminController - Integration Tests")
class GatewayRouteAdminControllerTest extends com.firstclub.membership.PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";
    private static final String BASE_PATH      = "/api/v2/admin/gateway-routes";

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
    private String routesUrl() { return base() + BASE_PATH; }

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

    private GatewayRouteRuleCreateRequestDTO cardInrRequest(String preferred, String fallback) {
        GatewayRouteRuleCreateRequestDTO req = new GatewayRouteRuleCreateRequestDTO();
        req.setPriority(10);
        req.setPaymentMethodType("CARD");
        req.setCurrency("INR");
        req.setRetryNumber(1);
        req.setPreferredGateway(preferred);
        req.setFallbackGateway(fallback);
        return req;
    }

    // ── POST /api/v2/admin/gateway-routes ─────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v2/admin/gateway-routes")
    class Create {

        @Test
        @DisplayName("201 – creates a route rule with preferred and fallback gateway")
        void creates_201() {
            ResponseEntity<GatewayRouteRuleResponseDTO> resp = rest.exchange(
                    routesUrl(), HttpMethod.POST,
                    new HttpEntity<>(cardInrRequest("razorpay", "stripe"), authHeaders()),
                    GatewayRouteRuleResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            GatewayRouteRuleResponseDTO body = resp.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getId()).isPositive();
            assertThat(body.getPreferredGateway()).isEqualTo("razorpay");
            assertThat(body.getFallbackGateway()).isEqualTo("stripe");
            assertThat(body.isActive()).isTrue();
            assertThat(body.getPaymentMethodType()).isEqualTo("CARD");
            assertThat(body.getCurrency()).isEqualTo("INR");
        }

        @Test
        @DisplayName("201 – creates a platform-wide rule (no merchantId)")
        void createsPlatformRule_201() {
            GatewayRouteRuleCreateRequestDTO req = cardInrRequest("payu", null);
            req.setPriority(99);

            ResponseEntity<GatewayRouteRuleResponseDTO> resp = rest.exchange(
                    routesUrl(), HttpMethod.POST,
                    new HttpEntity<>(req, authHeaders()),
                    GatewayRouteRuleResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getMerchantId()).isNull();
        }

        @Test
        @DisplayName("400 – validation error when required fields are missing")
        void missingRequiredFields_400() {
            GatewayRouteRuleCreateRequestDTO bad = new GatewayRouteRuleCreateRequestDTO();
            // paymentMethodType, currency, preferredGateway all absent

            ResponseEntity<Object> resp = rest.exchange(
                    routesUrl(), HttpMethod.POST,
                    new HttpEntity<>(bad, authHeaders()),
                    Object.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("401 – unauthenticated request is rejected")
        void unauthenticated_401() {
            ResponseEntity<Object> resp = rest.exchange(
                    routesUrl(), HttpMethod.POST,
                    new HttpEntity<>(cardInrRequest("razorpay", null), jsonHeaders()),
                    Object.class);

            assertThat(resp.getStatusCode().value()).isIn(401, 403);
        }
    }

    // ── GET /api/v2/admin/gateway-routes ──────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v2/admin/gateway-routes")
    class ListAll {

        @Test
        @DisplayName("200 – returns all route rules ordered by priority")
        void returnsAll_200() {
            // Seed two rules
            rest.exchange(routesUrl(), HttpMethod.POST,
                    new HttpEntity<>(cardInrRequest("razorpay", null), authHeaders()),
                    GatewayRouteRuleResponseDTO.class);
            rest.exchange(routesUrl(), HttpMethod.POST,
                    new HttpEntity<>(cardInrRequest("stripe", null), authHeaders()),
                    GatewayRouteRuleResponseDTO.class);

            ResponseEntity<List<GatewayRouteRuleResponseDTO>> resp = rest.exchange(
                    routesUrl(), HttpMethod.GET,
                    new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).isNotEmpty();
        }
    }

    // ── PUT /api/v2/admin/gateway-routes/{routeId} ────────────────────────────

    @Nested
    @DisplayName("PUT /api/v2/admin/gateway-routes/{routeId}")
    class Update {

        @Test
        @DisplayName("200 – updates priority and active flag")
        void updates_200() {
            // Create
            GatewayRouteRuleResponseDTO created = rest.exchange(
                    routesUrl(), HttpMethod.POST,
                    new HttpEntity<>(cardInrRequest("razorpay", "stripe"), authHeaders()),
                    GatewayRouteRuleResponseDTO.class).getBody();
            assertThat(created).isNotNull();

            // Update
            GatewayRouteRuleUpdateRequestDTO upd = new GatewayRouteRuleUpdateRequestDTO();
            upd.setPriority(50);
            upd.setActive(false);

            ResponseEntity<GatewayRouteRuleResponseDTO> resp = rest.exchange(
                    routesUrl() + "/" + created.getId(), HttpMethod.PUT,
                    new HttpEntity<>(upd, authHeaders()),
                    GatewayRouteRuleResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            GatewayRouteRuleResponseDTO body = resp.getBody();
            assertThat(body.getPriority()).isEqualTo(50);
            assertThat(body.isActive()).isFalse();
        }

        @Test
        @DisplayName("404 – returns 503/404 for unknown route rule id")
        void unknownId_404() {
            GatewayRouteRuleUpdateRequestDTO upd = new GatewayRouteRuleUpdateRequestDTO();
            upd.setPriority(1);

            ResponseEntity<Object> resp = rest.exchange(
                    routesUrl() + "/999999", HttpMethod.PUT,
                    new HttpEntity<>(upd, authHeaders()),
                    Object.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
