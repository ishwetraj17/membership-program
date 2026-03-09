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
import com.firstclub.payments.disputes.dto.DisputeCreateRequestDTO;
import com.firstclub.payments.disputes.dto.DisputeResponseDTO;
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
 * Integration tests for {@link com.firstclub.payments.disputes.controller.DisputeController}.
 *
 * <p>Uses a Testcontainers Postgres database (skipped without Docker).
 * All requests go through the full HTTP stack.
 */
@DisplayName("DisputeController — Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DisputeControllerTest extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private MerchantService merchantService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private LedgerAccountRepository ledgerAccountRepository;

    private String adminToken;
    private Long   merchantId;
    private Long   otherMerchantId;
    private Payment payment;

    @BeforeEach
    void setUp() {
        // 1. Authenticate
        ResponseEntity<JwtResponseDTO> auth = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new HttpEntity<>(LoginRequestDTO.builder()
                        .email(ADMIN_EMAIL).password(ADMIN_PASSWORD).build(), jsonHeaders()),
                JwtResponseDTO.class);
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminToken = auth.getBody().getToken();

        // 2. Seed ledger accounts needed by DisputeAccountingService
        seedAccount("PG_CLEARING",          LedgerAccount.AccountType.ASSET);
        seedAccount("DISPUTE_RESERVE",      LedgerAccount.AccountType.ASSET);
        seedAccount("CHARGEBACK_EXPENSE",   LedgerAccount.AccountType.EXPENSE);
        seedAccount("SUBSCRIPTION_LIABILITY", LedgerAccount.AccountType.LIABILITY);

        // 3. Create & activate two merchants (one for tenant-isolation tests)
        String ts = String.valueOf(System.nanoTime());
        MerchantResponseDTO m1 = merchantService.createMerchant(buildMerchant("DISP_M1_" + ts, ts + "a"));
        merchantService.updateMerchantStatus(m1.getId(), new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchantId = m1.getId();

        MerchantResponseDTO m2 = merchantService.createMerchant(buildMerchant("DISP_M2_" + ts, ts + "b"));
        merchantService.updateMerchantStatus(m2.getId(), new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        otherMerchantId = m2.getId();

        // 4. Create a CAPTURED payment (no gateway flow needed)
        BigDecimal capture = new BigDecimal("1000.00");
        payment = paymentRepository.save(Payment.builder()
                .paymentIntentId(Long.MAX_VALUE - System.nanoTime() % 1_000_000)
                .merchantId(merchantId)
                .amount(capture)
                .capturedAmount(capture)
                .refundedAmount(BigDecimal.ZERO)
                .disputedAmount(BigDecimal.ZERO)
                .netAmount(capture)
                .currency("INR")
                .status(PaymentStatus.CAPTURED)
                .gatewayTxnId("disp-ctrl-" + UUID.randomUUID())
                .capturedAt(LocalDateTime.now())
                .build());
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /disputes returns 201, payment moves to DISPUTED")
    void openDispute_returns201_paymentDisputed() {
        ResponseEntity<DisputeResponseDTO> resp = post(merchantId, payment.getId(),
                disputeRequest(new BigDecimal("300.00"), "FRAUDULENT_CHARGE"), "disp-key-1-" + payment.getId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        DisputeResponseDTO body = resp.getBody();
        assertThat(body.getId()).isNotNull();
        assertThat(body.getMerchantId()).isEqualTo(merchantId);
        assertThat(body.getPaymentId()).isEqualTo(payment.getId());
        assertThat(body.getAmount()).isEqualByComparingTo("300.00");
        assertThat(body.getStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(body.getPaymentStatusAfter()).isEqualTo(PaymentStatus.DISPUTED);
    }

    @Test
    @Order(2)
    @DisplayName("POST /disputes twice on same payment → 409 ACTIVE_DISPUTE_EXISTS")
    void openDispute_duplicate_returns409() {
        // First dispute
        post(merchantId, payment.getId(), disputeRequest(new BigDecimal("300.00"), "FRAUD"), "key-dup1-" + payment.getId());
        // Second dispute on same payment
        ResponseEntity<Object> resp = rest.exchange(
                disputeUrl(merchantId, payment.getId()),
                HttpMethod.POST,
                new HttpEntity<>(disputeRequest(new BigDecimal("100.00"), "DUPLICATE"),
                        authHeadersWithIdempotency("key-dup2-" + payment.getId())),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @Order(3)
    @DisplayName("POST /disputes with over-limit amount → 422")
    void openDispute_overLimit_returns422() {
        ResponseEntity<Object> resp = rest.exchange(
                disputeUrl(merchantId, payment.getId()),
                HttpMethod.POST,
                new HttpEntity<>(disputeRequest(new BigDecimal("5000.00"), "FRAUD"),
                        authHeadersWithIdempotency("key-over-" + payment.getId())),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @Order(4)
    @DisplayName("POST /disputes with wrong merchantId → 403")
    void openDispute_wrongMerchant_returns403() {
        ResponseEntity<Object> resp = rest.exchange(
                disputeUrl(otherMerchantId, payment.getId()),
                HttpMethod.POST,
                new HttpEntity<>(disputeRequest(new BigDecimal("300.00"), "FRAUD"),
                        authHeadersWithIdempotency("key-wrong-" + payment.getId())),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @Order(5)
    @DisplayName("GET /disputes returns list with the opened dispute")
    void listDisputes_returnsOpenedDispute() {
        post(merchantId, payment.getId(), disputeRequest(new BigDecimal("300.00"), "FRAUD"), "key-list-" + payment.getId());

        ResponseEntity<List<DisputeResponseDTO>> resp = rest.exchange(
                disputeUrl(merchantId, payment.getId()),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
        assertThat(resp.getBody().get(0).getPaymentId()).isEqualTo(payment.getId());
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
                .legalName("Dispute Test Corp " + emailSuffix)
                .displayName("Disp-" + code)
                .supportEmail("disp_" + emailSuffix + "@test.com")
                .defaultCurrency("INR")
                .countryCode("IN")
                .timezone("Asia/Kolkata")
                .build();
    }

    private DisputeCreateRequestDTO disputeRequest(BigDecimal amount, String reason) {
        return DisputeCreateRequestDTO.builder()
                .customerId(1L) // arbitrary — no FK constraint in JPA create-drop
                .amount(amount)
                .reasonCode(reason)
                .build();
    }

    private String base()                               { return "http://localhost:" + port; }
    private String disputeUrl(Long mid, Long pid)        { return base() + "/api/v2/merchants/" + mid + "/payments/" + pid + "/disputes"; }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(adminToken);
        return h;
    }

    private HttpHeaders authHeadersWithIdempotency(String key) {
        HttpHeaders h = authHeaders();
        h.set("Idempotency-Key", key);
        return h;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<DisputeResponseDTO> post(Long mid, Long pid,
                                                     DisputeCreateRequestDTO body, String ikey) {
        return rest.exchange(
                disputeUrl(mid, pid), HttpMethod.POST,
                new HttpEntity<>(body, authHeadersWithIdempotency(ikey)),
                DisputeResponseDTO.class);
    }
}
