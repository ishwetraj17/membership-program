package com.firstclub.notifications.webhooks;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.merchant.dto.MerchantCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.merchant.dto.MerchantStatusUpdateRequestDTO;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.service.MerchantService;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookDeliveryResponseDTO;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookEndpointCreateRequestDTO;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookEndpointResponseDTO;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookDeliveryStatus;
import com.firstclub.notifications.webhooks.service.MerchantWebhookDeliveryService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.notifications.webhooks.controller.MerchantWebhookDeliveryController}.
 *
 * <p>Uses Testcontainers Postgres (skipped without Docker).
 */
@DisplayName("MerchantWebhookDeliveryController — Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MerchantWebhookDeliveryControllerTest extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private MerchantService merchantService;

    /** Injected so we can enqueue deliveries programmatically without needing a live HTTP server. */
    @Autowired private MerchantWebhookDeliveryService deliveryService;

    private String adminToken;
    private Long   merchantId;
    private Long   otherMerchantId;
    private Long   endpointId;

    @BeforeEach
    void setUp() {
        ResponseEntity<JwtResponseDTO> auth = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new HttpEntity<>(LoginRequestDTO.builder()
                        .email(ADMIN_EMAIL).password(ADMIN_PASSWORD).build(), jsonHeaders()),
                JwtResponseDTO.class);
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminToken = auth.getBody().getToken();

        merchantId = createActiveMerchant("WDEL");
        otherMerchantId = createActiveMerchant("WDEL_OTHER");

        // Register a webhook endpoint for merchantId
        ResponseEntity<MerchantWebhookEndpointResponseDTO> epResp = rest.exchange(
                endpointsUrl(merchantId), HttpMethod.POST,
                new HttpEntity<>(MerchantWebhookEndpointCreateRequestDTO.builder()
                        .url("https://example.com/test-hook")
                        .subscribedEventsJson("[\"*\"]")
                        .active(true).build(), authHeaders()),
                MerchantWebhookEndpointResponseDTO.class);
        assertThat(epResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        endpointId = epResp.getBody().getId();
    }

    // ── GET list ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("GET /webhook-deliveries — empty initially → 200 []")
    void listDeliveries_emptyInitially_returns200EmptyList() {
        ResponseEntity<List<MerchantWebhookDeliveryResponseDTO>> resp = rest.exchange(
                deliveriesUrl(merchantId), HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("GET /webhook-deliveries — after enqueue → list has PENDING entry")
    void listDeliveries_afterEnqueue_returnsPendingDelivery() {
        deliveryService.enqueueDeliveryForMerchantEvent(
                merchantId, "invoice.paid", "{\"invoiceId\":1}");

        ResponseEntity<List<MerchantWebhookDeliveryResponseDTO>> resp = rest.exchange(
                deliveriesUrl(merchantId), HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<MerchantWebhookDeliveryResponseDTO> body = resp.getBody();
        assertThat(body).isNotEmpty();

        MerchantWebhookDeliveryResponseDTO delivery = body.get(0);
        assertThat(delivery.getEventType()).isEqualTo("invoice.paid");
        assertThat(delivery.getStatus()).isEqualTo(MerchantWebhookDeliveryStatus.PENDING);
        assertThat(delivery.getSignature()).startsWith("sha256=");
        assertThat(delivery.getEndpointId()).isEqualTo(endpointId);
    }

    @Test
    @Order(3)
    @DisplayName("GET /webhook-deliveries — tenant isolation: other merchant sees empty list")
    void listDeliveries_tenantIsolation_otherMerchantSeesEmpty() {
        deliveryService.enqueueDeliveryForMerchantEvent(
                merchantId, "payment.failed", "{\"paymentId\":99}");

        ResponseEntity<List<MerchantWebhookDeliveryResponseDTO>> resp = rest.exchange(
                deliveriesUrl(otherMerchantId), HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
    }

    // ── GET single ────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("GET /webhook-deliveries/{id} — correct merchant → 200 with full detail")
    void getDelivery_correctMerchant_returns200() {
        deliveryService.enqueueDeliveryForMerchantEvent(
                merchantId, "refund.completed", "{\"refundId\":77}");

        List<MerchantWebhookDeliveryResponseDTO> list = deliveryService.listDeliveriesForMerchant(merchantId);
        assertThat(list).isNotEmpty();
        Long deliveryId = list.get(0).getId();

        ResponseEntity<MerchantWebhookDeliveryResponseDTO> resp = rest.exchange(
                deliveriesUrl(merchantId) + "/" + deliveryId, HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                MerchantWebhookDeliveryResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        MerchantWebhookDeliveryResponseDTO body = resp.getBody();
        assertThat(body.getId()).isEqualTo(deliveryId);
        assertThat(body.getEventType()).isEqualTo("refund.completed");
        assertThat(body.getPayload()).isEqualTo("{\"refundId\":77}");
        assertThat(body.getSignature()).startsWith("sha256=");
    }

    @Test
    @Order(5)
    @DisplayName("GET /webhook-deliveries/{id} — wrong merchant → 404")
    void getDelivery_wrongMerchant_returns404() {
        deliveryService.enqueueDeliveryForMerchantEvent(
                merchantId, "dispute.opened", "{\"disputeId\":55}");

        List<MerchantWebhookDeliveryResponseDTO> list = deliveryService.listDeliveriesForMerchant(merchantId);
        assertThat(list).isNotEmpty();
        Long deliveryId = list.get(0).getId();

        // Try to fetch via the other merchant's URL
        ResponseEntity<Object> resp = rest.exchange(
                deliveriesUrl(otherMerchantId) + "/" + deliveryId, HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(6)
    @DisplayName("GET /webhook-deliveries/{id} — non-existent id → 404")
    void getDelivery_nonExistent_returns404() {
        ResponseEntity<Object> resp = rest.exchange(
                deliveriesUrl(merchantId) + "/999999", HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String base()                        { return "http://localhost:" + port; }
    private String endpointsUrl(Long mId)        { return base() + "/api/v2/merchants/" + mId + "/webhook-endpoints"; }
    private String deliveriesUrl(Long mId)       { return base() + "/api/v2/merchants/" + mId + "/webhook-deliveries"; }

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

    private Long createActiveMerchant(String prefix) {
        String code = prefix + "_" + System.nanoTime();
        MerchantResponseDTO merchant = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder()
                        .merchantCode(code).legalName("Webhook Delivery Test Corp " + code)
                        .displayName("WDT-" + code).supportEmail(code + "@test.com")
                        .defaultCurrency("INR").countryCode("IN").timezone("Asia/Kolkata")
                        .build());
        merchantService.updateMerchantStatus(
                merchant.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        return merchant.getId();
    }
}
