package com.firstclub.risk;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.risk.dto.RiskRuleCreateRequestDTO;
import com.firstclub.risk.entity.RiskAction;
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
 * Integration tests for {@link com.firstclub.risk.controller.RiskRuleAdminController}.
 */
@DisplayName("RiskRuleAdminController — Integration Tests")
class RiskRuleAdminControllerTest extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = login();
    }

    // ── POST / ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST / — 201 created with valid rule body")
    void createRule_validRequest_returns201() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/risk/rules",
                HttpMethod.POST,
                new HttpEntity<>(validCreateRequest(), auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsKey("id");
        assertThat(resp.getBody().get("ruleCode")).isEqualTo("INTEG_TEST_RULE");
        assertThat(resp.getBody().get("action")).isEqualTo("BLOCK");
    }

    @Test
    @DisplayName("POST / — 401 without auth")
    void createRule_requiresAuth() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/risk/rules",
                HttpMethod.POST,
                new HttpEntity<>(validCreateRequest(), json()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    // ── GET / ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET / — 200 with paginated results")
    void listRules_returns200WithPage() {
        // Seed at least one rule
        rest.exchange(base() + "/api/v2/admin/risk/rules",
                HttpMethod.POST, new HttpEntity<>(validCreateRequest(), auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/risk/rules",
                HttpMethod.GET, new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("content");
    }

    @Test
    @DisplayName("GET / — 401 without auth")
    void listRules_requiresAuth() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/risk/rules",
                HttpMethod.GET, new HttpEntity<>(json()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    // ── PUT /{ruleId} ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT /{ruleId} — 200 with updated fields")
    void updateRule_returns200() {
        // Create first
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                base() + "/api/v2/admin/risk/rules",
                HttpMethod.POST, new HttpEntity<>(validCreateRequest(), auth()), new ParameterizedTypeReference<Map<String, Object>>() {});
        Long ruleId = ((Number) created.getBody().get("id")).longValue();

        RiskRuleCreateRequestDTO updateReq = RiskRuleCreateRequestDTO.builder()
                .ruleCode("UPDATED_RULE").ruleType("BLOCKLIST_IP")
                .configJson("{\"score\":100}").action(RiskAction.REVIEW)
                .active(false).priority(5).build();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/risk/rules/" + ruleId,
                HttpMethod.PUT, new HttpEntity<>(updateReq, auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("ruleCode")).isEqualTo("UPDATED_RULE");
        assertThat(resp.getBody().get("action")).isEqualTo("REVIEW");
        assertThat(resp.getBody().get("active")).isEqualTo(false);
    }

    @Test
    @DisplayName("PUT /{ruleId} — 404 for non-existent rule")
    void updateRule_notFound_returns404() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/risk/rules/999999",
                HttpMethod.PUT, new HttpEntity<>(validCreateRequest(), auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

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

    private RiskRuleCreateRequestDTO validCreateRequest() {
        return RiskRuleCreateRequestDTO.builder()
                .ruleCode("INTEG_TEST_RULE")
                .ruleType("BLOCKLIST_IP")
                .configJson("{\"score\":100}")
                .action(RiskAction.BLOCK)
                .active(true)
                .priority(1)
                .build();
    }
}
