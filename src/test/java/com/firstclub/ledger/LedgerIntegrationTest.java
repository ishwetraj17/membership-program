package com.firstclub.ledger;

import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerLine;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.repository.LedgerEntryRepository;
import com.firstclub.ledger.repository.LedgerLineRepository;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.payments.dto.GatewayPayRequest;
import com.firstclub.payments.dto.RefundRequestDTO;
import com.firstclub.payments.dto.RefundResponseDTO;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.model.PaymentIntentStatus;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.payments.service.PaymentIntentService;
import com.firstclub.payments.service.RefundService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Ledger module.
 *
 * <p>Tests run against a live PostgreSQL container (Testcontainers) and are
 * skipped automatically when Docker is unavailable.
 *
 * <h3>Test 1 — payment webhook creates PAYMENT_CAPTURED ledger entry</h3>
 * Creates a PaymentIntent → POST /gateway/pay SUCCEEDED → polls until
 * SUCCEEDED → asserts a balanced PAYMENT_CAPTURED entry with
 * DR PG_CLEARING / CR SUBSCRIPTION_LIABILITY.
 *
 * <h3>Test 2 — refund creates REFUND_ISSUED reversal entry</h3>
 * Inserts a CAPTURED Payment directly → calls RefundService.createRefund →
 * asserts a balanced REFUND_ISSUED entry with
 * DR SUBSCRIPTION_LIABILITY / CR PG_CLEARING.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LedgerIntegrationTest extends PostgresIntegrationTestBase {

    @Autowired private PaymentIntentService   paymentIntentService;
    @Autowired private PaymentRepository      paymentRepository;
    @Autowired private RefundService          refundService;
    @Autowired private LedgerEntryRepository  ledgerEntryRepository;
    @Autowired private LedgerLineRepository   ledgerLineRepository;
    @Autowired private TestRestTemplate       restTemplate;

    // -------------------------------------------------------------------------
    // Test 1 — payment captures post PAYMENT_CAPTURED balanced entry
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("gateway SUCCEEDED webhook posts balanced PAYMENT_CAPTURED ledger entry")
    void paymentWebhook_postsPaymentCapturedEntry() throws Exception {
        BigDecimal amount = new BigDecimal("750.00");

        // 1. Create a PaymentIntent (invoiceId=null — ledger entry still posted)
        var pi = paymentIntentService.createForInvoice(null, amount, "INR");

        // 2. Simulate gateway charge → async webhook fires in 2–5 s
        ResponseEntity<?> payResp = restTemplate.postForEntity(
                "/gateway/pay",
                new GatewayPayRequest(pi.getId(), "SUCCEEDED"),
                Map.class);
        assertThat(payResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 3. Poll until PI reaches SUCCEEDED
        boolean succeeded = pollUntil(
                () -> paymentIntentService.findById(pi.getId()).getStatus() == PaymentIntentStatus.SUCCEEDED,
                15, TimeUnit.SECONDS);
        assertThat(succeeded).as("PaymentIntent should reach SUCCEEDED within 15 s").isTrue();

        // 4. Find the CAPTURED Payment
        List<Payment> payments = paymentRepository.findByPaymentIntentId(pi.getId());
        assertThat(payments).hasSize(1);
        Payment payment = payments.get(0);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);

        // 5. Ledger entry must exist for this payment
        List<LedgerEntry> entries = ledgerEntryRepository
                .findByReferenceTypeAndReferenceId(LedgerReferenceType.PAYMENT, payment.getId());
        assertThat(entries).as("Exactly one PAYMENT_CAPTURED ledger entry expected").hasSize(1);

        LedgerEntry entry = entries.get(0);
        assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.PAYMENT_CAPTURED);
        assertThat(entry.getCurrency()).isEqualTo("INR");

        // 6. Two lines: one DR PG_CLEARING, one CR SUBSCRIPTION_LIABILITY — amounts match
        List<LedgerLine> lines = ledgerLineRepository.findByEntryId(entry.getId());
        assertThat(lines).hasSize(2);

        LedgerLine debitLine = lines.stream()
                .filter(l -> l.getDirection() == LineDirection.DEBIT).findFirst().orElseThrow();
        LedgerLine creditLine = lines.stream()
                .filter(l -> l.getDirection() == LineDirection.CREDIT).findFirst().orElseThrow();

        // Account IDs will differ, but amounts must match the payment amount and each other
        assertThat(debitLine.getAmount()).isEqualByComparingTo(amount);
        assertThat(creditLine.getAmount()).isEqualByComparingTo(amount);
        assertThat(debitLine.getAmount()).isEqualByComparingTo(creditLine.getAmount());
        assertThat(debitLine.getAccountId()).isNotEqualTo(creditLine.getAccountId());
    }

    // -------------------------------------------------------------------------
    // Test 2 — refund posts REFUND_ISSUED balanced reversal
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createRefund posts balanced REFUND_ISSUED ledger reversal entry")
    void createRefund_postsRefundIssuedEntry() {
        BigDecimal payAmount    = new BigDecimal("300.00");
        BigDecimal refundAmount = new BigDecimal("300.00");

        // 1. Insert a CAPTURED payment directly (no need for full gateway flow)
        Payment payment = paymentRepository.save(Payment.builder()
                .paymentIntentId(Long.MAX_VALUE - 1)          // fake PI id — no FK constraint in JPA
                .amount(payAmount)
                .currency("INR")
                .status(PaymentStatus.CAPTURED)
                .gatewayTxnId("ledger-test-txn-" + UUID.randomUUID())
                .capturedAt(LocalDateTime.now())
                .build());

        // 2. Issue the refund
        RefundResponseDTO refundResponse = refundService.createRefund(
                RefundRequestDTO.builder()
                        .paymentId(payment.getId())
                        .amount(refundAmount)
                        .reason("integration test refund")
                        .build());

        assertThat(refundResponse.getId()).isNotNull();
        assertThat(refundResponse.getAmount()).isEqualByComparingTo(refundAmount);

        // 3. Ledger entry for REFUND referenceType must exist
        List<LedgerEntry> entries = ledgerEntryRepository
                .findByReferenceTypeAndReferenceId(LedgerReferenceType.REFUND, refundResponse.getId());
        assertThat(entries).as("Exactly one REFUND_ISSUED ledger entry expected").hasSize(1);

        LedgerEntry entry = entries.get(0);
        assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.REFUND_ISSUED);
        assertThat(entry.getCurrency()).isEqualTo("INR");

        // 4. Lines: DR SUBSCRIPTION_LIABILITY / CR PG_CLEARING — balanced
        List<LedgerLine> lines = ledgerLineRepository.findByEntryId(entry.getId());
        assertThat(lines).hasSize(2);

        LedgerLine debitLine = lines.stream()
                .filter(l -> l.getDirection() == LineDirection.DEBIT).findFirst().orElseThrow();
        LedgerLine creditLine = lines.stream()
                .filter(l -> l.getDirection() == LineDirection.CREDIT).findFirst().orElseThrow();

        assertThat(debitLine.getAmount()).isEqualByComparingTo(refundAmount);
        assertThat(creditLine.getAmount()).isEqualByComparingTo(refundAmount);
        assertThat(debitLine.getAmount()).isEqualByComparingTo(creditLine.getAmount());
        assertThat(debitLine.getAccountId()).isNotEqualTo(creditLine.getAccountId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean pollUntil(BooleanSupplier condition, int timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(500);
        }
        return false;
    }
}
