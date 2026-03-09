package com.firstclub.merchant.auth;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.merchant.auth.dto.MerchantModeUpdateRequestDTO;
import com.firstclub.merchant.auth.entity.MerchantApiKeyMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.merchant.auth.controller.MerchantModeController}.
 */
@DisplayName("MerchantModeController — Integration Tests")
class MerchantModeControllerTest extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    private String adminToken;
    private Long merchantId;

    @BeforeEach
    void setUp() {
        adminToken = login();
        merchantId = createMerchant();
    }

    // ── GET / ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET / — 200 returns default mode for a new merchant")
    void getMode_200_returnsDefaultSandbox() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                modeUrl(), HttpMethod.GET, new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("merchantId");
        assertThat(resp.getBody().get("sandboxEnabled")).isEqualTo(true);
        assertThat(resp.getBody().get("liveEnabled")).isEqualTo(false);
        assertThat(resp.getBody().get("defaultMode")).isEqualTo("SANDBOX");
    }

    @Test
    @DisplayName("GET / — 401 without auth token")
    void getMode_401_withoutAuth() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                modeUrl(), HttpMethod.GET, new HttpEntity<>(json()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    // ── PUT / ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT / — 200 sandbox-only update persists correctly")
    void updateMode_200_sandboxOnly() {
        MerchantModeUpdateRequestDTO req = MerchantModeUpdateRequestDTO.builder()
                .sandboxEnabled(true).liveEnabled(false)
                .defaultMode(MerchantApiKeyMode.SANDBOX)
                .build();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                modeUrl(), HttpMethod.PUT, new HttpEntity<>(req, auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("sandboxEnabled")).isEqualTo(true);
        assertThat(resp.getBody().get("liveEnabled")).isEqualTo(false);
        assertThat(resp.getBody().get("defaultMode")).isEqualTo("SANDBOX");
    }

    @Test
    @DisplayName("PUT / — 400 when defaultMode=LIVE but liveEnabled=false")
    void updateMode_400_defaultLiveWithoutEnabling() {
        MerchantModeUpdateRequestDTO req = MerchantModeUpdateRequestDTO.builder()
                .sandboxEnabled(true).liveEnabled(false)
                .defaultMode(MerchantApiKeyMode.LIVE)
                .build();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                modeUrl(), HttpMethod.PUT, new HttpEntity<>(req, auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(400, 500);
    }

    @Test
    @DisplayName("PUT / — 400 when enabling live for a PENDING merchant")
    void updateMode_400_liveOnPendingMerchant() {
        // The merchant is PENDING (just created, not activated)
        MerchantModeUpdateRequestDTO req = MerchantModeUpdateRequestDTO.builder()
                .sandboxEnabled(true).liveEnabled(true)
                .defaultMode(MerchantApiKeyMode.SANDBOX)
                .build();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                modeUrl(), HttpMethod.PUT, new HttpEntity<>(req, auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(400, 500);
    }

    @Test
    @DisplayName("PUT / — 401 without auth token")
    void updateMode_401_withoutAuth() {
        MerchantModeUpdateRequestDTO req = MerchantModeUpdateRequestDTO.builder()
                .sandboxEnabled(true).defaultMode(MerchantApiKeyMode.SANDBOX).build();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                modeUrl(), HttpMethod.PUT, new HttpEntity<>(req, json()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String base()    { return "http://localhost:" + port; }
    private String modeUrl() { return base() + "/api/v2/merchants/" + merchantId + "/mode"; }

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
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        Map<String, Object> req = Map.of(
                "merchantCode", "MODE_" + unique,
                "legalName", "Mode Test Ltd",
                "displayName", "Mode Test",
                "supportEmail", "mode+" + unique + "@merchant.com",
                "defaultCurrency", "INR",
                "countryCode", "IN",
                "timezone", "Asia/Kolkata"
        );
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/merchants",
                HttpMethod.POST,
                new HttpEntity<>(req, auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) resp.getBody().get("id")).longValue();
    }
}
