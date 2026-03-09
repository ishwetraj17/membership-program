package com.firstclub.reporting.projections;

import com.firstclub.events.dto.EventMetadataDTO;
import com.firstclub.events.service.DomainEventLog;
import com.firstclub.events.service.DomainEventTypes;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.reporting.projections.dto.RebuildResponseDTO;
import com.firstclub.reporting.projections.service.ProjectionRebuildService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.reporting.projections.controller.ProjectionAdminController}.
 * Requires Docker (Testcontainers Postgres).
 */
@DisplayName("ProjectionAdminController — Integration Tests")
class ProjectionAdminControllerTest extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DomainEventLog domainEventLog;
    @Autowired ProjectionRebuildService projectionRebuildService;

    private String adminToken;
    private Long merchantId;
    private Long customerId;

    @BeforeEach
    void setUp() {
        // Authenticate
        ResponseEntity<JwtResponseDTO> auth = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new HttpEntity<>(LoginRequestDTO.builder()
                        .email(ADMIN_EMAIL).password(ADMIN_PASSWORD).build(), json()),
                JwtResponseDTO.class);
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminToken = auth.getBody().getToken();

        // Use fixed merchant/customer IDs for the domain events
        merchantId = 9000L + System.nanoTime() % 1000;
        customerId = 8000L + System.nanoTime() % 1000;
    }

    // ── GET /customer-billing-summary ────────────────────────────────────────

    @Test
    @DisplayName("GET /customer-billing-summary — 200 (empty page initially)")
    void getCustomerBillingSummary_returnsEmptyPage() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/projections/customer-billing-summary",
                HttpMethod.GET,
                new HttpEntity<>(auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /customer-billing-summary — 401 without auth")
    void getCustomerBillingSummary_requiresAuth() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/projections/customer-billing-summary",
                HttpMethod.GET,
                new HttpEntity<>(json()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    // ── GET /merchant-kpis/daily ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /merchant-kpis/daily — 200 with merchant filter")
    void getMerchantDailyKpis_withMerchantFilter() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/projections/merchant-kpis/daily?merchantId=" + merchantId,
                HttpMethod.GET,
                new HttpEntity<>(auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── POST /rebuild/{projectionName} ───────────────────────────────────────

    @Test
    @DisplayName("POST /rebuild/customer_billing_summary — 200 with rebuild stats")
    void rebuildCustomerBilling_returns200() {
        // Seed one domain event so rebuild has something to process
        domainEventLog.record(
                DomainEventTypes.INVOICE_CREATED,
                Map.of("customerId", customerId, "invoiceId", "INV-PROJ-001"),
                EventMetadataDTO.builder().merchantId(merchantId).build());

        ResponseEntity<RebuildResponseDTO> resp = rest.exchange(
                base() + "/api/v2/admin/projections/rebuild/customer_billing_summary",
                HttpMethod.POST,
                new HttpEntity<>(auth()),
                RebuildResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getProjectionName()).isEqualTo("customer_billing_summary");
        assertThat(resp.getBody().getEventsProcessed()).isGreaterThanOrEqualTo(1);
        assertThat(resp.getBody().getRebuiltAt()).isNotNull();
    }

    @Test
    @DisplayName("POST /rebuild/merchant_daily_kpi — 200 returns kpi projection stats")
    void rebuildMerchantKpi_returns200() {
        ResponseEntity<RebuildResponseDTO> resp = rest.exchange(
                base() + "/api/v2/admin/projections/rebuild/merchant_daily_kpi",
                HttpMethod.POST,
                new HttpEntity<>(auth()),
                RebuildResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getProjectionName()).isEqualTo("merchant_daily_kpi");
    }

    @Test
    @DisplayName("POST /rebuild/invalid_name — 400 Bad Request")
    void rebuildUnknownProjection_returns400() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/projections/rebuild/unknown_projection",
                HttpMethod.POST,
                new HttpEntity<>(auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── End-to-end: seed event → rebuild → verify via GET ────────────────────

    @Test
    @DisplayName("PAYMENT_SUCCEEDED event is reflected in customer billing summary after rebuild")
    void endToEnd_paymentSucceeded_appearsInBillingSummaryAfterRebuild() {
        domainEventLog.record(
                DomainEventTypes.PAYMENT_SUCCEEDED,
                Map.of("customerId", customerId, "amount", "750.00"),
                EventMetadataDTO.builder().merchantId(merchantId).build());

        // Rebuild the projection
        projectionRebuildService.rebuildCustomerBillingSummaryProjection();

        // Retrieve via controller
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/projections/customer-billing-summary"
                        + "?merchantId=" + merchantId + "&customerId=" + customerId,
                HttpMethod.GET,
                new HttpEntity<>(auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The projection page should now exist and not be empty
        Map<?, ?> body = resp.getBody();
        assertThat(body).isNotNull();
        // "totalElements" from Spring Pageable
        assertThat(((Number) body.get("totalElements")).intValue()).isGreaterThanOrEqualTo(1);
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
}
