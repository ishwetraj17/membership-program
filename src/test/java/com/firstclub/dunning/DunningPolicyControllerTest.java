package com.firstclub.dunning;

import com.firstclub.dunning.dto.DunningPolicyCreateRequestDTO;
import com.firstclub.dunning.dto.DunningPolicyResponseDTO;
import com.firstclub.dunning.entity.DunningTerminalStatus;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.merchant.dto.MerchantCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.merchant.dto.MerchantStatusUpdateRequestDTO;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.service.MerchantService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.dunning.controller.DunningPolicyController}.
 *
 * <p>Uses Testcontainers Postgres (skipped without Docker).
 */
@DisplayName("DunningPolicyController — Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DunningPolicyControllerTest extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private MerchantService  merchantService;

    private String adminToken;
    private Long   merchantId;

    @BeforeEach
    void setUp() {
        // Authenticate
        ResponseEntity<JwtResponseDTO> auth = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new HttpEntity<>(LoginRequestDTO.builder()
                        .email(ADMIN_EMAIL).password(ADMIN_PASSWORD).build(), jsonHeaders()),
                JwtResponseDTO.class);
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminToken = auth.getBody().getToken();

        // Create and activate a fresh merchant per test (timestamp-unique code)
        String code = "DPOL_" + System.nanoTime();
        MerchantResponseDTO merchant = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder()
                        .merchantCode(code)
                        .legalName("Dunning Policy Test Corp " + code)
                        .displayName("DPT-" + code)
                        .supportEmail(code + "@test.com")
                        .defaultCurrency("INR")
                        .countryCode("IN")
                        .timezone("Asia/Kolkata")
                        .build());
        merchantService.updateMerchantStatus(
                merchant.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchantId = merchant.getId();
    }

    // ── POST ──────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /dunning-policies → 201 with policy details")
    void createPolicy_returns201() {
        ResponseEntity<DunningPolicyResponseDTO> resp = post(merchantId, validRequest("STANDARD"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        DunningPolicyResponseDTO body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getId()).isNotNull();
        assertThat(body.getMerchantId()).isEqualTo(merchantId);
        assertThat(body.getPolicyCode()).isEqualTo("STANDARD");
        assertThat(body.getMaxAttempts()).isEqualTo(4);
        assertThat(body.getStatusAfterExhaustion()).isEqualTo(DunningTerminalStatus.SUSPENDED);
    }

    @Test
    @Order(2)
    @DisplayName("POST /dunning-policies — duplicate policyCode → 409")
    void createPolicy_duplicate_returns409() {
        post(merchantId, validRequest("DUPLICATE_TEST"));

        ResponseEntity<Object> second = rest.exchange(
                policiesUrl(merchantId), HttpMethod.POST,
                new HttpEntity<>(validRequest("DUPLICATE_TEST"), authHeaders()),
                Object.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @Order(3)
    @DisplayName("POST /dunning-policies — invalid retryOffsetsJson → 422")
    void createPolicy_invalidOffsets_returns422() {
        DunningPolicyCreateRequestDTO bad = DunningPolicyCreateRequestDTO.builder()
                .policyCode("BAD_POLICY")
                .retryOffsetsJson("not-an-array")
                .maxAttempts(3)
                .graceDays(5)
                .statusAfterExhaustion("SUSPENDED")
                .build();

        ResponseEntity<Object> resp = rest.exchange(
                policiesUrl(merchantId), HttpMethod.POST,
                new HttpEntity<>(bad, authHeaders()),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @Order(4)
    @DisplayName("POST /dunning-policies — negative offset in array → 422")
    void createPolicy_negativeOffset_returns422() {
        DunningPolicyCreateRequestDTO bad = validRequest("NEG").toBuilder()
                .retryOffsetsJson("[60, -30, 1440]").build();

        ResponseEntity<Object> resp = rest.exchange(
                policiesUrl(merchantId), HttpMethod.POST,
                new HttpEntity<>(bad, authHeaders()),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ── GET list ──────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("GET /dunning-policies → 200 with list of policies")
    void listPolicies_returns200() {
        post(merchantId, validRequest("POL_A"));
        post(merchantId, validRequest("POL_B"));

        ResponseEntity<List<DunningPolicyResponseDTO>> resp = rest.exchange(
                policiesUrl(merchantId), HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).extracting(DunningPolicyResponseDTO::getPolicyCode)
                .contains("POL_A", "POL_B");
    }

    // ── GET by code ───────────────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("GET /dunning-policies/{policyCode} → 200")
    void getPolicyByCode_returns200() {
        post(merchantId, validRequest("LOOKUP_TEST"));

        ResponseEntity<DunningPolicyResponseDTO> resp = rest.exchange(
                policiesUrl(merchantId) + "/LOOKUP_TEST", HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                DunningPolicyResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getPolicyCode()).isEqualTo("LOOKUP_TEST");
    }

    @Test
    @Order(7)
    @DisplayName("GET /dunning-policies/{policyCode} — not found → 404")
    void getPolicyByCode_notFound_returns404() {
        ResponseEntity<Object> resp = rest.exchange(
                policiesUrl(merchantId) + "/NONEXISTENT", HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String base()                   { return "http://localhost:" + port; }
    private String policiesUrl(Long mId)    { return base() + "/api/v2/merchants/" + mId + "/dunning-policies"; }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(adminToken);
        return h;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private DunningPolicyCreateRequestDTO validRequest(String code) {
        return DunningPolicyCreateRequestDTO.builder()
                .policyCode(code)
                .retryOffsetsJson("[60, 360, 1440, 4320]")
                .maxAttempts(4)
                .graceDays(7)
                .fallbackToBackupPaymentMethod(false)
                .statusAfterExhaustion("SUSPENDED")
                .build();
    }

    private ResponseEntity<DunningPolicyResponseDTO> post(Long mId,
                                                           DunningPolicyCreateRequestDTO req) {
        return rest.exchange(
                policiesUrl(mId), HttpMethod.POST,
                new HttpEntity<>(req, authHeaders()),
                DunningPolicyResponseDTO.class);
    }
}
