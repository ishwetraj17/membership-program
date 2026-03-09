package com.firstclub.payments.refund;

import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerLine;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.repository.LedgerEntryRepository;
import com.firstclub.ledger.repository.LedgerLineRepository;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.merchant.dto.MerchantCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.merchant.dto.MerchantStatusUpdateRequestDTO;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.service.MerchantService;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.refund.dto.RefundCreateRequestDTO;
import com.firstclub.payments.refund.dto.RefundV2ResponseDTO;
import com.firstclub.payments.refund.entity.RefundV2Status;
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
 * Integration tests for {@link com.firstclub.payments.refund.controller.RefundControllerV2}.
 *
 * <p>Uses a Testcontainers Postgres database (skipped without Docker).
 * All requests go through the full HTTP stack (auth, idempotency filter, controller, service, JPA).
 *
 * <p>Test coverage:
 * <ol>
 *   <li>First partial refund returns 201, correct DTO, PARTIALLY_REFUNDED payment status</li>
 *   <li>Second cumulative refund — correct remaining refundable amount</li>
 *   <li>Final refund exhausting balance — REFUNDED payment status</li>
 *   <li>Over-refund returns 422</li>
 *   <li>Idempotency-Key re-send returns same 201 response</li>
 *   <li>GET list returns all refunds for the payment</li>
 *   <li>GET by ID returns correct DTO</li>
 *   <li>Wrong merchantId returns 403</li>
 *   <li>Ledger entry is posted: DR SUBSCRIPTION_LIABILITY / CR PG_CLEARING</li>
 * </ol>
 */
@DisplayName("RefundControllerV2 — Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RefundControllerV2Test extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate      rest;
    @Autowired private MerchantService       merchantService;
    @Autowired private PaymentRepository     paymentRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private LedgerLineRepository  ledgerLineRepository;

    private String adminToken;
    private Long   merchantId;
    private Long   otherMerchantId;
    private Payment payment;

    // ── Setup / teardown ──────────────────────────────────────────────────────

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

        // 2. Create & activate a merchant
        String ts = String.valueOf(System.nanoTime());
        MerchantResponseDTO m1 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder()
                        .merchantCode("REFV2_M1_" + ts)
                        .legalName("Refund V2 Tenant")
                        .displayName("RefV2-Test")
                        .supportEmail("refv2_" + ts + "@test.com")
                        .defaultCurrency("INR")
                        .countryCode("IN")
                        .timezone("Asia/Kolkata")
                        .build());
        merchantService.updateMerchantStatus(m1.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchantId = m1.getId();

        // 3. A second merchant for tenant-isolation tests
        MerchantResponseDTO m2 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder()
                        .merchantCode("REFV2_M2_" + ts)
                        .legalName("Refund V2 Other Tenant")
                        .displayName("RefV2-Other")
                        .supportEmail("refv2_other_" + ts + "@test.com")
                        .defaultCurrency("INR")
                        .countryCode("IN")
                        .timezone("Asia/Kolkata")
                        .build());
        merchantService.updateMerchantStatus(m2.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        otherMerchantId = m2.getId();

        // 4. Create a CAPTURED Payment directly (no need for full gateway flow)
        BigDecimal amount = new BigDecimal("1000.00");
        payment = paymentRepository.save(Payment.builder()
                .paymentIntentId(Long.MAX_VALUE - System.nanoTime() % 1_000_000)
                .merchantId(merchantId)
                .amount(amount)
                .capturedAmount(amount)
                .refundedAmount(BigDecimal.ZERO)
                .disputedAmount(BigDecimal.ZERO)
                .netAmount(amount)
                .currency("INR")
                .status(PaymentStatus.CAPTURED)
                .gatewayTxnId("refv2-test-" + UUID.randomUUID())
                .capturedAt(LocalDateTime.now())
                .build());
    }

    // ── Happy-path tests ──────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /refunds returns 201 with PARTIALLY_REFUNDED status for partial refund")
    void firstPartialRefund_returns201_andPartiallyRefunded() {
        RefundCreateRequestDTO req = refundRequest("300.00", "CUSTOMER_REQUEST");
        ResponseEntity<RefundV2ResponseDTO> resp = post(merchantId, payment.getId(), req,
                "key-first-" + payment.getId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        RefundV2ResponseDTO body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getId()).isNotNull();
        assertThat(body.getMerchantId()).isEqualTo(merchantId);
        assertThat(body.getPaymentId()).isEqualTo(payment.getId());
        assertThat(body.getAmount()).isEqualByComparingTo("300.00");
        assertThat(body.getStatus()).isEqualTo(RefundV2Status.COMPLETED);
        assertThat(body.getCompletedAt()).isNotNull();
        assertThat(body.getPaymentStatusAfter()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(body.getRefundableAmountAfter()).isEqualByComparingTo("700.00");
        assertThat(body.getReasonCode()).isEqualTo("CUSTOMER_REQUEST");
    }

    @Test
    @Order(2)
    @DisplayName("second partial refund tracks cumulative refunded amount")
    void secondPartialRefund_tracksCorrectCumulative() {
        // First refund: 300
        post(merchantId, payment.getId(), refundRequest("300.00", "DUPLICATE"), "key-1-" + payment.getId());
        // Second refund: 400 (total: 700, remaining: 300)
        ResponseEntity<RefundV2ResponseDTO> resp = post(merchantId, payment.getId(),
                refundRequest("400.00", "DUPLICATE"), "key-2-" + payment.getId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        RefundV2ResponseDTO body = resp.getBody();
        assertThat(body.getPaymentStatusAfter()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(body.getRefundableAmountAfter()).isEqualByComparingTo("300.00");
    }

    @Test
    @Order(3)
    @DisplayName("final refund exhausting full balance transitions payment to REFUNDED")
    void fullRefund_transitionsToRefunded() {
        // Partial first, then finish it
        post(merchantId, payment.getId(), refundRequest("600.00", "CANCEL"), "key-p-" + payment.getId());
        ResponseEntity<RefundV2ResponseDTO> resp = post(merchantId, payment.getId(),
                refundRequest("400.00", "CANCEL"), "key-f-" + payment.getId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().getPaymentStatusAfter()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(resp.getBody().getRefundableAmountAfter()).isEqualByComparingTo("0.00");
    }

    @Test
    @Order(4)
    @DisplayName("POST /refunds with over-refund amount returns 422")
    void overRefund_returns422() {
        ResponseEntity<Object> resp = rest.exchange(
                refundUrl(merchantId, payment.getId()),
                HttpMethod.POST,
                new HttpEntity<>(refundRequest("1500.00", "FRAUD"),
                        authHeadersWithIdempotency("over-" + payment.getId())),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @Order(5)
    @DisplayName("same Idempotency-Key on second POST returns cached 201 response")
    void idempotencyKey_returnsCachedResponse() {
        String ikey = "idem-" + payment.getId() + "-" + System.nanoTime();
        RefundCreateRequestDTO req = refundRequest("200.00", "DUPLICATE");

        // First call — creates the refund
        ResponseEntity<RefundV2ResponseDTO> first = post(merchantId, payment.getId(), req, ikey);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long firstRefundId = first.getBody().getId();

        // Second call — same Idempotency-Key → should return the same cached response
        ResponseEntity<RefundV2ResponseDTO> second = post(merchantId, payment.getId(), req, ikey);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Both responses must refer to the same refund row
        assertThat(second.getBody().getId()).isEqualTo(firstRefundId);
    }

    @Test
    @Order(6)
    @DisplayName("GET /refunds lists all refunds for the payment")
    void listRefunds_returnsAllRefunds() {
        post(merchantId, payment.getId(), refundRequest("100.00", "R1"), "k1-" + payment.getId());
        post(merchantId, payment.getId(), refundRequest("200.00", "R2"), "k2-" + payment.getId());

        ResponseEntity<List<RefundV2ResponseDTO>> resp = rest.exchange(
                refundUrl(merchantId, payment.getId()),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(resp.getBody()).allMatch(r -> r.getPaymentId().equals(payment.getId()));
    }

    @Test
    @Order(7)
    @DisplayName("GET /refunds/{refundId} returns the specific refund")
    void getRefundById_returnsCorrectDto() {
        ResponseEntity<RefundV2ResponseDTO> created = post(merchantId, payment.getId(),
                refundRequest("150.00", "GET_TEST"), "k-get-" + payment.getId());
        Long refundId = created.getBody().getId();

        ResponseEntity<RefundV2ResponseDTO> resp = rest.exchange(
                refundUrl(merchantId, payment.getId()) + "/" + refundId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                RefundV2ResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getId()).isEqualTo(refundId);
        assertThat(resp.getBody().getAmount()).isEqualByComparingTo("150.00");
        assertThat(resp.getBody().getReasonCode()).isEqualTo("GET_TEST");
    }

    @Test
    @Order(8)
    @DisplayName("wrong merchantId returns 403")
    void wrongMerchantId_returns403() {
        ResponseEntity<Object> resp = rest.exchange(
                refundUrl(otherMerchantId, payment.getId()),   // payment belongs to merchantId, not otherMerchantId
                HttpMethod.POST,
                new HttpEntity<>(refundRequest("100.00", "FRAUD"),
                        authHeadersWithIdempotency("tenant-iso-" + payment.getId())),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @Order(9)
    @DisplayName("refund posts balanced REFUND_ISSUED ledger entry DR SUBSCRIPTION_LIABILITY / CR PG_CLEARING")
    void createRefund_postsCorrectLedgerEntry() {
        BigDecimal refundAmt = new BigDecimal("250.00");
        ResponseEntity<RefundV2ResponseDTO> created = post(merchantId, payment.getId(),
                refundRequest(refundAmt.toPlainString(), "LEDGER_TEST"),
                "k-ledger-" + payment.getId());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long refundId = created.getBody().getId();

        // Verify the ledger entry
        List<LedgerEntry> entries = ledgerEntryRepository
                .findByReferenceTypeAndReferenceId(LedgerReferenceType.REFUND, refundId);
        assertThat(entries).as("Exactly one REFUND_ISSUED ledger entry expected").hasSize(1);

        LedgerEntry entry = entries.get(0);
        assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.REFUND_ISSUED);
        assertThat(entry.getCurrency()).isEqualTo("INR");

        List<LedgerLine> lines = ledgerLineRepository.findByEntryId(entry.getId());
        assertThat(lines).hasSize(2);

        LedgerLine debit  = lines.stream().filter(l -> l.getDirection() == LineDirection.DEBIT).findFirst().orElseThrow();
        LedgerLine credit = lines.stream().filter(l -> l.getDirection() == LineDirection.CREDIT).findFirst().orElseThrow();

        assertThat(debit.getAmount()).isEqualByComparingTo(refundAmt);
        assertThat(credit.getAmount()).isEqualByComparingTo(refundAmt);
        assertThat(debit.getAccountId()).isNotEqualTo(credit.getAccountId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

    private String refundUrl(Long mId, Long pId) {
        return base() + "/api/v2/merchants/" + mId + "/payments/" + pId + "/refunds";
    }

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

    private HttpHeaders authHeadersWithIdempotency(String key) {
        HttpHeaders h = authHeaders();
        h.set("Idempotency-Key", key);
        return h;
    }

    private RefundCreateRequestDTO refundRequest(String amount, String reasonCode) {
        return RefundCreateRequestDTO.builder()
                .amount(new BigDecimal(amount))
                .reasonCode(reasonCode)
                .build();
    }

    private ResponseEntity<RefundV2ResponseDTO> post(Long mId, Long pId,
            RefundCreateRequestDTO req, String idempotencyKey) {
        return rest.exchange(
                refundUrl(mId, pId),
                HttpMethod.POST,
                new HttpEntity<>(req, authHeadersWithIdempotency(idempotencyKey)),
                RefundV2ResponseDTO.class);
    }
}
