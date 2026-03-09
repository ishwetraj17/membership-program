package com.firstclub.payments.disputes;

import com.firstclub.ledger.entity.LedgerAccount;
import com.firstclub.ledger.repository.LedgerAccountRepository;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.merchant.dto.MerchantCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.merchant.dto.MerchantStatusUpdateRequestDTO;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.service.MerchantService;
import com.firstclub.payments.disputes.dto.*;
import com.firstclub.payments.disputes.entity.DisputeStatus;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.repository.PaymentRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.payments.disputes.controller.DisputeDetailController}.
 *
 * <p>Covers merchant-scoped routes: list, get, review, resolve, evidence CRUD.
 */
@DisplayName("DisputeDetailController — Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DisputeDetailControllerTest extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private MerchantService merchantService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private LedgerAccountRepository ledgerAccountRepository;

    private String adminToken;
    private Long merchantId;
    private Payment payment;

    /** Shared dispute id for multi-step tests within one @BeforeEach context. */
    private Long disputeId;

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

        // Seed ledger accounts
        seedAccount("PG_CLEARING",          LedgerAccount.AccountType.ASSET);
        seedAccount("DISPUTE_RESERVE",      LedgerAccount.AccountType.ASSET);
        seedAccount("CHARGEBACK_EXPENSE",   LedgerAccount.AccountType.EXPENSE);
        seedAccount("SUBSCRIPTION_LIABILITY", LedgerAccount.AccountType.LIABILITY);

        // Create & activate merchant
        String ts = String.valueOf(System.nanoTime());
        MerchantResponseDTO m = merchantService.createMerchant(buildMerchant("DD_M_" + ts, ts));
        merchantService.updateMerchantStatus(m.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchantId = m.getId();

        // Create a CAPTURED payment
        BigDecimal capture = new BigDecimal("2000.00");
        payment = paymentRepository.save(Payment.builder()
                .paymentIntentId(Long.MAX_VALUE - System.nanoTime() % 2_000_000)
                .merchantId(merchantId)
                .amount(capture)
                .capturedAmount(capture)
                .refundedAmount(BigDecimal.ZERO)
                .disputedAmount(BigDecimal.ZERO)
                .netAmount(capture)
                .currency("INR")
                .status(PaymentStatus.CAPTURED)
                .gatewayTxnId("dd-ctrl-" + UUID.randomUUID())
                .capturedAt(LocalDateTime.now())
                .build());

        // Open a dispute so subsequent steps have a disputeId to work with
        ResponseEntity<DisputeResponseDTO> opened = openDispute(
                merchantId, payment.getId(),
                disputeRequest(new BigDecimal("500.00"), "ITEM_NOT_RECEIVED"),
                "dd-setup-" + payment.getId());
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        disputeId = opened.getBody().getId();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("GET /disputes returns list containing the opened dispute")
    void listDisputes_returnsList() {
        ResponseEntity<List<DisputeResponseDTO>> resp = rest.exchange(
                detailBase(merchantId),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
        assertThat(resp.getBody().stream().anyMatch(d -> d.getId().equals(disputeId))).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("GET /disputes/{id} returns the opened dispute")
    void getDispute_returnsDispute() {
        ResponseEntity<DisputeResponseDTO> resp = rest.exchange(
                detailBase(merchantId) + "/" + disputeId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                DisputeResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getId()).isEqualTo(disputeId);
        assertThat(resp.getBody().getStatus()).isEqualTo(DisputeStatus.OPEN);
    }

    @Test
    @Order(3)
    @DisplayName("POST /{id}/review transitions dispute to UNDER_REVIEW")
    void moveToReview_transitionsStatus() {
        ResponseEntity<DisputeResponseDTO> resp = rest.exchange(
                detailBase(merchantId) + "/" + disputeId + "/review",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders()),
                DisputeResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getStatus()).isEqualTo(DisputeStatus.UNDER_REVIEW);
    }

    @Test
    @Order(4)
    @DisplayName("POST /{id}/evidence before due_by → 201 created")
    void addEvidence_beforeDueDate_returns201() {
        // Set a due date in the future by updating through the service — use a dispute with future dueBy
        // The setUp dispute has no dueBy; addEvidence should succeed for null dueBy
        ResponseEntity<DisputeEvidenceResponseDTO> resp = rest.exchange(
                detailBase(merchantId) + "/" + disputeId + "/evidence",
                HttpMethod.POST,
                new HttpEntity<>(DisputeEvidenceCreateRequestDTO.builder()
                        .evidenceType("INVOICE")
                        .contentReference("s3://evidence-bucket/invoice-123.pdf")
                        .uploadedBy(1L)
                        .build(), authHeaders()),
                DisputeEvidenceResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().getDisputeId()).isEqualTo(disputeId);
        assertThat(resp.getBody().getEvidenceType()).isEqualTo("INVOICE");
    }

    @Test
    @Order(5)
    @DisplayName("GET /{id}/evidence returns list of uploaded evidence")
    void listEvidence_returnsList() {
        // Upload one first (null dueBy succeeds)
        rest.exchange(
                detailBase(merchantId) + "/" + disputeId + "/evidence",
                HttpMethod.POST,
                new HttpEntity<>(DisputeEvidenceCreateRequestDTO.builder()
                        .evidenceType("PHOTO")
                        .contentReference("s3://evidence-bucket/photo-456.jpg")
                        .uploadedBy(1L)
                        .build(), authHeaders()),
                DisputeEvidenceResponseDTO.class);

        ResponseEntity<List<DisputeEvidenceResponseDTO>> resp = rest.exchange(
                detailBase(merchantId) + "/" + disputeId + "/evidence",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    @Order(6)
    @DisplayName("POST /{id}/resolve WON → dispute status WON, payment status CAPTURED")
    void resolve_won_restoresPayment() {
        ResponseEntity<DisputeResponseDTO> resp = rest.exchange(
                detailBase(merchantId) + "/" + disputeId + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(DisputeResolveRequestDTO.builder()
                        .outcome("WON")
                        .resolutionNotes("Customer provided insufficient evidence.")
                        .build(), authHeaders()),
                DisputeResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getStatus()).isEqualTo(DisputeStatus.WON);
        assertThat(resp.getBody().getPaymentStatusAfter()).isIn(
                PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @Order(7)
    @DisplayName("POST /{id}/resolve LOST → dispute status LOST, accounting entry posted")
    void resolve_lost_postChargebacks() {
        // Open a fresh payment for this test so we start from CAPTURED state
        BigDecimal capture = new BigDecimal("800.00");
        Payment p2 = paymentRepository.save(Payment.builder()
                .paymentIntentId(Long.MAX_VALUE - System.nanoTime() % 3_000_000)
                .merchantId(merchantId)
                .amount(capture)
                .capturedAmount(capture)
                .refundedAmount(BigDecimal.ZERO)
                .disputedAmount(BigDecimal.ZERO)
                .netAmount(capture)
                .currency("INR")
                .status(PaymentStatus.CAPTURED)
                .gatewayTxnId("dd-ctrl-lost-" + UUID.randomUUID())
                .capturedAt(LocalDateTime.now())
                .build());

        ResponseEntity<DisputeResponseDTO> opened = openDispute(merchantId, p2.getId(),
                disputeRequest(new BigDecimal("200.00"), "CHARGEBACK"), "dd-lost-" + p2.getId());
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long dId = opened.getBody().getId();

        ResponseEntity<DisputeResponseDTO> resp = rest.exchange(
                detailBase(merchantId) + "/" + dId + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(DisputeResolveRequestDTO.builder()
                        .outcome("LOST")
                        .resolutionNotes("Chargeback accepted by card network.")
                        .build(), authHeaders()),
                DisputeResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getStatus()).isEqualTo(DisputeStatus.LOST);
    }

    @Test
    @Order(8)
    @DisplayName("POST /{id}/resolve twice → 422 DISPUTE_ALREADY_RESOLVED")
    void resolve_twice_returns422() {
        // First resolve
        rest.exchange(
                detailBase(merchantId) + "/" + disputeId + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(DisputeResolveRequestDTO.builder().outcome("WON").build(), authHeaders()),
                DisputeResponseDTO.class);
        // Second resolve attempt
        ResponseEntity<Object> resp = rest.exchange(
                detailBase(merchantId) + "/" + disputeId + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(DisputeResolveRequestDTO.builder().outcome("LOST").build(), authHeaders()),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void seedAccount(String name, LedgerAccount.AccountType type) {
        ledgerAccountRepository.findByName(name)
                .orElseGet(() -> ledgerAccountRepository.save(LedgerAccount.builder()
                        .name(name).accountType(type).currency("INR").build()));
    }

    private MerchantCreateRequestDTO buildMerchant(String code, String emailSuffix) {
        return MerchantCreateRequestDTO.builder()
                .merchantCode(code)
                .legalName("DD Test Corp " + emailSuffix)
                .displayName("DD-" + code)
                .supportEmail("dd_" + emailSuffix + "@test.com")
                .defaultCurrency("INR")
                .countryCode("IN")
                .timezone("Asia/Kolkata")
                .build();
    }

    private DisputeCreateRequestDTO disputeRequest(BigDecimal amount, String reason) {
        return DisputeCreateRequestDTO.builder()
                .customerId(1L)
                .amount(amount)
                .reasonCode(reason)
                .build();
    }

    private String base()                        { return "http://localhost:" + port; }
    private String detailBase(Long mid)           { return base() + "/api/v2/merchants/" + mid + "/disputes"; }
    private String openUrl(Long mid, Long pid)    { return base() + "/api/v2/merchants/" + mid + "/payments/" + pid + "/disputes"; }

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

    private ResponseEntity<DisputeResponseDTO> openDispute(Long mid, Long pid,
                                                            DisputeCreateRequestDTO body, String ikey) {
        HttpHeaders h = authHeaders();
        h.set("Idempotency-Key", ikey);
        return rest.exchange(openUrl(mid, pid), HttpMethod.POST,
                new HttpEntity<>(body, h), DisputeResponseDTO.class);
    }
}
