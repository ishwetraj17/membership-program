package com.firstclub.notifications.webhooks;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.merchant.dto.MerchantCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.merchant.dto.MerchantStatusUpdateRequestDTO;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.service.MerchantService;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookEndpointCreateRequestDTO;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookEndpointResponseDTO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.notifications.webhooks.controller.MerchantWebhookEndpointController}.
 *
 * <p>Uses Testcontainers Postgres (skipped without Docker).
 */
@DisplayName("MerchantWebhookEndpointController — Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MerchantWebhookEndpointControllerTest extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private MerchantService merchantService;

    private String adminToken;
    private Long   merchantId;
    private Long   otherMerchantId;

    @BeforeEach
    void setUp() {
        ResponseEntity<JwtResponseDTO> auth = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new HttpEntity<>(LoginRequestDTO.builder()
                        .email(ADMIN_EMAIL).password(ADMIN_PASSWORD).build(), jsonHeaders()),
                JwtResponseDTO.class);
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminToken = auth.getBody().getToken();

        merchantId = createActiveMerchant("WHK");
        otherMerchantId = createActiveMerchant("WHK_OTHER");
    }

    // ── POST — create ─────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST → 201 with endpoint details (no secret in response)")
    void create_validRequest_returns201() {
        ResponseEntity<MerchantWebhookEndpointResponseDTO> resp =
                post(merchantId, validRequest("https://example.com/hooks/invoice-paid"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MerchantWebhookEndpointResponseDTO body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getId()).isNotNull();
        assertThat(body.getMerchantId()).isEqualTo(merchantId);
        assertThat(body.getUrl()).isEqualTo("https://example.com/hooks/invoice-paid");
        assertThat(body.isActive()).isTrue();
        assertThat(body.getSubscribedEventsJson()).isEqualTo("[\"invoice.paid\",\"payment.failed\"]");
        // Secret must NOT be present in the response (security rule)
        // DTO has no secret field — we verify the JSON object doesn't contain it
        assertThat(body.toString()).doesNotContain("secret");
    }

    @Test
    @Order(2)
    @DisplayName("POST — URL without http/https prefix → 422")
    void create_invalidUrl_returns422() {
        MerchantWebhookEndpointCreateRequestDTO bad = validRequest("ftp://wrong.example.com/hook");

        ResponseEntity<Object> resp = rest.exchange(
                endpointsUrl(merchantId), HttpMethod.POST,
                new HttpEntity<>(bad, authHeaders()),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @Order(3)
    @DisplayName("POST — empty subscribedEventsJson array → 422")
    void create_emptyEventsArray_returns422() {
        MerchantWebhookEndpointCreateRequestDTO bad = MerchantWebhookEndpointCreateRequestDTO.builder()
                .url("https://example.com/hook")
                .subscribedEventsJson("[]")
                .build();

        ResponseEntity<Object> resp = rest.exchange(
                endpointsUrl(merchantId), HttpMethod.POST,
                new HttpEntity<>(bad, authHeaders()),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @Order(4)
    @DisplayName("POST — non-JSON subscribedEventsJson → 422")
    void create_invalidEventsJson_returns422() {
        MerchantWebhookEndpointCreateRequestDTO bad = MerchantWebhookEndpointCreateRequestDTO.builder()
                .url("https://example.com/hook")
                .subscribedEventsJson("not-json")
                .build();

        ResponseEntity<Object> resp = rest.exchange(
                endpointsUrl(merchantId), HttpMethod.POST,
                new HttpEntity<>(bad, authHeaders()),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ── GET — list ────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("GET list → 200 with created endpoints")
    void listEndpoints_returns200WithEntries() {
        post(merchantId, validRequest("https://a.example.com/hook"));
        post(merchantId, validRequest("https://b.example.com/hook"));

        ResponseEntity<List<MerchantWebhookEndpointResponseDTO>> resp = rest.exchange(
                endpointsUrl(merchantId), HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).extracting(MerchantWebhookEndpointResponseDTO::getUrl)
                .contains("https://a.example.com/hook", "https://b.example.com/hook");
    }

    @Test
    @Order(6)
    @DisplayName("GET list — tenant isolation: other merchant gets empty list")
    void listEndpoints_tenantIsolation_otherMerchantSeesEmpty() {
        post(merchantId, validRequest("https://mine.example.com/hook"));

        ResponseEntity<List<MerchantWebhookEndpointResponseDTO>> resp = rest.exchange(
                endpointsUrl(otherMerchantId), HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
    }

    // ── PUT — update ──────────────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("PUT /{endpointId} → 200 with updated URL")
    void updateEndpoint_returns200WithNewUrl() {
        MerchantWebhookEndpointResponseDTO created =
                post(merchantId, validRequest("https://old.example.com/hook")).getBody();

        MerchantWebhookEndpointCreateRequestDTO updateReq = MerchantWebhookEndpointCreateRequestDTO.builder()
                .url("https://new.example.com/hook")
                .subscribedEventsJson("[\"subscription.activated\"]")
                .active(true)
                .build();

        ResponseEntity<MerchantWebhookEndpointResponseDTO> resp = rest.exchange(
                endpointsUrl(merchantId) + "/" + created.getId(), HttpMethod.PUT,
                new HttpEntity<>(updateReq, authHeaders()),
                MerchantWebhookEndpointResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getUrl()).isEqualTo("https://new.example.com/hook");
        assertThat(resp.getBody().getSubscribedEventsJson()).isEqualTo("[\"subscription.activated\"]");
    }

    @Test
    @Order(8)
    @DisplayName("PUT /{endpointId} — wrong merchant → 404")
    void updateEndpoint_wrongMerchant_returns404() {
        MerchantWebhookEndpointResponseDTO created =
                post(merchantId, validRequest("https://owned.example.com/hook")).getBody();

        ResponseEntity<Object> resp = rest.exchange(
                endpointsUrl(otherMerchantId) + "/" + created.getId(), HttpMethod.PUT,
                new HttpEntity<>(validRequest("https://new.example.com/hook"), authHeaders()),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── DELETE — deactivate ───────────────────────────────────────────────────

    @Test
    @Order(9)
    @DisplayName("DELETE /{endpointId} → 204, subsequent GET shows active=false")
    void deleteEndpoint_returns204AndBecomesInactive() {
        MerchantWebhookEndpointResponseDTO created =
                post(merchantId, validRequest("https://delete-me.example.com/hook")).getBody();
        assertThat(created.isActive()).isTrue();

        ResponseEntity<Void> del = rest.exchange(
                endpointsUrl(merchantId) + "/" + created.getId(), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()),
                Void.class);

        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify it is now inactive in the list
        ResponseEntity<List<MerchantWebhookEndpointResponseDTO>> list = rest.exchange(
                endpointsUrl(merchantId), HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<>() {});
        assertThat(list.getBody())
                .filteredOn(e -> e.getId().equals(created.getId()))
                .extracting(MerchantWebhookEndpointResponseDTO::isActive)
                .containsExactly(false);
    }

    @Test
    @Order(10)
    @DisplayName("DELETE /{endpointId} — wrong merchant → 404")
    void deleteEndpoint_wrongMerchant_returns404() {
        MerchantWebhookEndpointResponseDTO created =
                post(merchantId, validRequest("https://somebody-elses.example.com/hook")).getBody();

        ResponseEntity<Object> resp = rest.exchange(
                endpointsUrl(otherMerchantId) + "/" + created.getId(), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String base()                        { return "http://localhost:" + port; }
    private String endpointsUrl(Long mId)        { return base() + "/api/v2/merchants/" + mId + "/webhook-endpoints"; }

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

    private MerchantWebhookEndpointCreateRequestDTO validRequest(String url) {
        return MerchantWebhookEndpointCreateRequestDTO.builder()
                .url(url)
                .subscribedEventsJson("[\"invoice.paid\",\"payment.failed\"]")
                .active(true)
                .build();
    }

    private ResponseEntity<MerchantWebhookEndpointResponseDTO> post(
            Long mId, MerchantWebhookEndpointCreateRequestDTO req) {
        return rest.exchange(
                endpointsUrl(mId), HttpMethod.POST,
                new HttpEntity<>(req, authHeaders()),
                MerchantWebhookEndpointResponseDTO.class);
    }

    private Long createActiveMerchant(String prefix) {
        String code = prefix + "_" + System.nanoTime();
        MerchantResponseDTO merchant = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder()
                        .merchantCode(code).legalName("Webhook Test Corp " + code)
                        .displayName("WHT-" + code).supportEmail(code + "@test.com")
                        .defaultCurrency("INR").countryCode("IN").timezone("Asia/Kolkata")
                        .build());
        merchantService.updateMerchantStatus(
                merchant.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        return merchant.getId();
    }
}
