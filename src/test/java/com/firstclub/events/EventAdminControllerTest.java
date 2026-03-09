package com.firstclub.events;

import com.firstclub.events.dto.EventListResponseDTO;
import com.firstclub.events.service.DomainEventLog;
import com.firstclub.events.service.DomainEventTypes;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.events.controller.EventAdminController}.
 *
 * <p>Uses Testcontainers Postgres (skipped without Docker).
 * Records live domain events via {@link DomainEventLog} then queries through
 * the REST API to verify filtering and pagination.
 */
@DisplayName("EventAdminController — Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventAdminControllerTest extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private DomainEventLog domainEventLog;

    private String adminToken;

    @BeforeAll
    void setUp() {
        ResponseEntity<JwtResponseDTO> auth = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new HttpEntity<>(LoginRequestDTO.builder()
                        .email(ADMIN_EMAIL).password(ADMIN_PASSWORD).build(), jsonHeaders()),
                JwtResponseDTO.class);
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminToken = auth.getBody().getToken();

        // Seed a few events for tests to query
        domainEventLog.record(DomainEventTypes.INVOICE_CREATED,
                Map.of("invoiceId", 1001, "merchantId", 42));
        domainEventLog.record(DomainEventTypes.PAYMENT_SUCCEEDED,
                Map.of("invoiceId", 1001, "merchantId", 42));
        domainEventLog.record(DomainEventTypes.SUBSCRIPTION_ACTIVATED,
                Map.of("subscriptionId", 55, "merchantId", 99));
    }

    // ── GET /api/v2/admin/events — no filters ─────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("GET /api/v2/admin/events — returns 200 with a page of events")
    void listEvents_noFilters_returns200() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/events",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = resp.getBody();
        assertThat(body).isNotNull();
        // Spring Page serialises to { "content": [...], "totalElements": n, ... }
        assertThat((Integer) body.get("totalElements")).isGreaterThanOrEqualTo(3);
    }

    // ── GET with eventType filter ─────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("GET /api/v2/admin/events?eventType=INVOICE_CREATED — filtered results")
    void listEvents_filterByEventType_returnsOnlyMatchingType() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/events?eventType=" + DomainEventTypes.INVOICE_CREATED,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> content = (List<?>) resp.getBody().get("content");
        assertThat(content).isNotEmpty();
        content.forEach(item -> {
            Map<?, ?> event = (Map<?, ?>) item;
            assertThat(event.get("eventType")).isEqualTo(DomainEventTypes.INVOICE_CREATED);
        });
    }

    // ── Unauthenticated access is rejected ───────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("GET /api/v2/admin/events — no token → 401 or 403")
    void listEvents_noToken_returns401or403() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Object> resp = rest.exchange(
                base() + "/api/v2/admin/events",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Object.class);

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    // ── Pagination ───────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("GET /api/v2/admin/events?size=1 — pagination gives 1 item per page")
    void listEvents_paginationSizeOne_returnsOnlyOneEvent() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/events?size=1&page=0",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> content = (List<?>) resp.getBody().get("content");
        assertThat(content).hasSize(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

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
}
