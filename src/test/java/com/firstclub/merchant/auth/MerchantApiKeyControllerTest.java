package com.firstclub.merchant.auth;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.merchant.auth.dto.MerchantApiKeyCreateRequestDTO;
import com.firstclub.merchant.auth.entity.MerchantApiKeyMode;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.merchant.auth.controller.MerchantApiKeyController}.
 */
@DisplayName("MerchantApiKeyController — Integration Tests")
class MerchantApiKeyControllerTest extends PostgresIntegrationTestBase {

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

    // ── POST / ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST / — 201 with rawKey in response (shown once)")
    void createApiKey_201_withRawKey() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                apiKeysUrl(),
                HttpMethod.POST,
                new HttpEntity<>(validCreateRequest(), auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsKey("id");
        assertThat(resp.getBody()).containsKey("rawKey");
        assertThat(resp.getBody()).containsKey("keyPrefix");
        assertThat((String) resp.getBody().get("rawKey")).startsWith("fc_sb_");
        assertThat(resp.getBody().get("status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("POST / — 401 without auth token")
    void createApiKey_401_withoutAuth() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                apiKeysUrl(),
                HttpMethod.POST,
                new HttpEntity<>(validCreateRequest(), json()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    @DisplayName("POST / — 400 for empty scopes")
    void createApiKey_400_emptyScopes() {
        MerchantApiKeyCreateRequestDTO badReq = MerchantApiKeyCreateRequestDTO.builder()
                .mode(MerchantApiKeyMode.SANDBOX)
                .scopes(List.of())
                .build();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                apiKeysUrl(),
                HttpMethod.POST,
                new HttpEntity<>(badReq, auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── GET / ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET / — 200 with list (empty initially, then populated after create)")
    void listApiKeys_200() {
        // Create one key first
        rest.exchange(apiKeysUrl(), HttpMethod.POST,
                new HttpEntity<>(validCreateRequest(), auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        ResponseEntity<List> resp = rest.exchange(
                apiKeysUrl(),
                HttpMethod.GET,
                new HttpEntity<>(auth()),
                List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    @DisplayName("GET / list response does NOT contain rawKey or keyHash")
    void listApiKeys_doesNotExposeSecretMaterial() {
        rest.exchange(apiKeysUrl(), HttpMethod.POST,
                new HttpEntity<>(validCreateRequest(), auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        ResponseEntity<List> resp = rest.exchange(
                apiKeysUrl(), HttpMethod.GET, new HttpEntity<>(auth()), List.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) resp.getBody().get(0);
        assertThat(first).doesNotContainKey("rawKey");
        assertThat(first).doesNotContainKey("keyHash");
        assertThat(first).containsKey("keyPrefix");
    }

    // ── DELETE /{id} ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /{id} — 204 revokes an active key")
    void revokeApiKey_204() {
        // Create a key first
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                apiKeysUrl(), HttpMethod.POST,
                new HttpEntity<>(validCreateRequest(), auth()), new ParameterizedTypeReference<Map<String, Object>>() {});
        Long keyId = ((Number) created.getBody().get("id")).longValue();

        ResponseEntity<Void> revokeResp = rest.exchange(
                apiKeysUrl() + "/" + keyId,
                HttpMethod.DELETE,
                new HttpEntity<>(auth()),
                Void.class);

        assertThat(revokeResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify: key now shows REVOKED in list
        ResponseEntity<List> list = rest.exchange(
                apiKeysUrl(), HttpMethod.GET, new HttpEntity<>(auth()), List.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> key = (Map<String, Object>) list.getBody().get(0);
        assertThat(key.get("status")).isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("DELETE /{id} — 404 for non-existent key")
    void revokeApiKey_404_nonExistent() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                apiKeysUrl() + "/999999",
                HttpMethod.DELETE,
                new HttpEntity<>(auth()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String base()       { return "http://localhost:" + port; }
    private String apiKeysUrl() { return base() + "/api/v2/merchants/" + merchantId + "/api-keys"; }

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
                "merchantCode", "APIKEY_" + unique,
                "legalName", "Api Key Test Ltd",
                "displayName", "ApiKey Test",
                "supportEmail", "test+" + unique + "@merchant.com",
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

    private MerchantApiKeyCreateRequestDTO validCreateRequest() {
        return MerchantApiKeyCreateRequestDTO.builder()
                .mode(MerchantApiKeyMode.SANDBOX)
                .scopes(List.of("customers:read", "payments:read"))
                .build();
    }
}
