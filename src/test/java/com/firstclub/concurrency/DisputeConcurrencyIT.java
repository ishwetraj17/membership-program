package com.firstclub.concurrency;

import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.payments.disputes.dto.DisputeCreateRequestDTO;
import com.firstclub.payments.disputes.dto.DisputeResponseDTO;
import com.firstclub.payments.disputes.entity.DisputeStatus;
import com.firstclub.payments.disputes.dto.DisputeResolveRequestDTO;
import com.firstclub.payments.disputes.service.DisputeService;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.refund.dto.RefundCreateRequestDTO;
import com.firstclub.payments.refund.entity.RefundV2;
import com.firstclub.payments.refund.repository.RefundV2Repository;
import com.firstclub.payments.refund.service.RefundServiceV2;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.payments.repository.PaymentRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9 — Refund & Dispute Capacity Concurrency Integration Tests.
 *
 * <p>Proves that neither the refund path nor the dispute path can overrun the
 * captured amount of a {@link Payment} under concurrent load:
 *
 * <ol>
 *   <li><b>50-thread refund storm</b> — 50 threads simultaneously attempt a ₹10
 *       refund on a payment with {@code capturedAmount = ₹100}.  At most 10 can
 *       succeed; the rest are rejected with {@code OVER_REFUND}.  The payment row
 *       is checked for invariant correctness after all threads finish.</li>
 *   <li><b>Refund + dispute race</b> — One thread refunds ₹50 and another
 *       concurrently opens a ₹60 dispute on the same ₹100 payment.  The sum of
 *       refunded + disputed can never exceed ₹100.</li>
 *   <li><b>DB capacity constraint</b> — Direct JDBC update that attempts to set
 *       {@code refunded_amount_minor > captured_amount_minor} must be rejected
 *       by the {@code chk_payment_capacity} CHECK constraint.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Dispute & Refund Capacity Concurrency — Phase 9")
class DisputeConcurrencyIT extends PostgresIntegrationTestBase {

    @Autowired private RefundServiceV2           refundServiceV2;
    @Autowired private DisputeService            disputeService;
    @Autowired private PaymentRepository         paymentRepository;
    @Autowired private RefundV2Repository        refundV2Repository;
    @Autowired private PaymentIntentV2Repository paymentIntentV2Repository;
    @Autowired private MerchantAccountRepository merchantAccountRepository;
    @Autowired private CustomerRepository        customerRepository;
    @Autowired private JdbcTemplate              jdbcTemplate;

    private MerchantAccount merchant;
    private Customer        customer;

    @BeforeAll
    void seedSharedFixtures() {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        merchant = merchantAccountRepository.save(MerchantAccount.builder()
                .merchantCode("DCIT_" + uid)
                .legalName("Dispute Concurrency Test")
                .displayName("Dispute Concurrency Test")
                .supportEmail("dcit_" + uid + "@test.com")
                .status(MerchantStatus.ACTIVE)
                .build());

        customer = customerRepository.save(Customer.builder()
                .merchant(merchant)
                .email("dccust_" + uid + "@test.com")
                .fullName("Dispute Concurrency Customer")
                .build());
    }

    // ── 1. 50-thread refund storm ─────────────────────────────────────────────

    @Test
    @DisplayName("50 × ₹10 concurrent refunds on a ₹100 payment — at most 10 succeed, no over-refund")
    void concurrentRefundStorm_50threads_noOverRefund() throws Exception {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        PaymentIntentV2 intent = paymentIntentV2Repository.save(PaymentIntentV2.builder()
                .merchant(merchant)
                .customer(customer)
                .amount(new BigDecimal("100.0000"))
                .currency("INR")
                .clientSecret("cs_50rf_" + uid)
                .status(PaymentIntentStatusV2.SUCCEEDED)
                .build());

        Payment payment = paymentRepository.save(Payment.builder()
                .paymentIntentId(intent.getId())
                .merchantId(merchant.getId())
                .amount(new BigDecimal("100.00"))
                .currency("INR")
                .status(PaymentStatus.CAPTURED)
                .gatewayTxnId("gw_50rf_" + uid)
                .capturedAmount(new BigDecimal("100.0000"))
                // capturedAmountMinor = 100 × 10_000 = 1_000_000
                .capturedAmountMinor(1_000_000L)
                .build());

        final Long merchantId = merchant.getId();
        final Long paymentId  = payment.getId();

        int threads = 50;
        CyclicBarrier       barrier = new CyclicBarrier(threads);
        ExecutorService     pool    = Executors.newFixedThreadPool(threads);
        AtomicInteger       okCount = new AtomicInteger();
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            // Each thread uses a distinct fingerprint so no idempotency fast-path is triggered
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await();
                try {
                    refundServiceV2.createRefund(merchantId, paymentId,
                            RefundCreateRequestDTO.builder()
                                    .amount(new BigDecimal("10.00"))
                                    .reasonCode("CUSTOMER_REQUEST")
                                    .requestFingerprint("fp_50rf_" + uid + "_" + idx)
                                    .build());
                    okCount.incrementAndGet();
                    return "ok";
                } catch (Exception e) {
                    return "fail:" + e.getMessage();
                }
            }));
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        Payment refreshed = paymentRepository.findById(paymentId).orElseThrow();

        // Core invariant: refundedAmount must never exceed capturedAmount
        assertThat(refreshed.getRefundedAmount())
                .as("refundedAmount must not exceed capturedAmount — no over-refund")
                .isLessThanOrEqualTo(refreshed.getCapturedAmount());

        // Minor-unit invariant: same check on integer columns
        assertThat(refreshed.getRefundedAmountMinor())
                .as("refunded_amount_minor must not exceed captured_amount_minor")
                .isLessThanOrEqualTo(refreshed.getCapturedAmountMinor());

        // With capturedAmount=100 and each request=10, at most 10 can succeed
        assertThat(okCount.get())
                .as("at most 10 refunds of 10 fit within 100 capturedAmount")
                .isLessThanOrEqualTo(10);

        // Verify the DB row count matches success count
        List<RefundV2> committed = refundV2Repository.findByPaymentId(paymentId);
        assertThat(committed).hasSizeLessThanOrEqualTo(10);

        BigDecimal sumRefunds = committed.stream()
                .map(RefundV2::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumRefunds)
                .as("sum of refund amounts must match payment.refundedAmount")
                .isEqualByComparingTo(refreshed.getRefundedAmount());
    }

    // ── 2. Concurrent refund + dispute race ───────────────────────────────────

    @Test
    @DisplayName("Concurrent ₹50 refund + ₹60 dispute on ₹100 payment — combined never exceeds capacity")
    void concurrentRefundAndDispute_noCapacityOverrun() throws Exception {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        PaymentIntentV2 intent = paymentIntentV2Repository.save(PaymentIntentV2.builder()
                .merchant(merchant)
                .customer(customer)
                .amount(new BigDecimal("100.0000"))
                .currency("INR")
                .clientSecret("cs_rdrce_" + uid)
                .status(PaymentIntentStatusV2.SUCCEEDED)
                .build());

        // CAPTURED payment with capturedAmount = 100
        Payment payment = paymentRepository.save(Payment.builder()
                .paymentIntentId(intent.getId())
                .merchantId(merchant.getId())
                .amount(new BigDecimal("100.00"))
                .currency("INR")
                .status(PaymentStatus.CAPTURED)
                .gatewayTxnId("gw_rdrce_" + uid)
                .capturedAmount(new BigDecimal("100.0000"))
                .capturedAmountMinor(1_000_000L)
                .build());

        final Long merchantId = merchant.getId();
        final Long paymentId  = payment.getId();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Thread A: refund ₹50
        Future<String> refundFuture = pool.submit(() -> {
            barrier.await();
            try {
                refundServiceV2.createRefund(merchantId, paymentId,
                        RefundCreateRequestDTO.builder()
                                .amount(new BigDecimal("50.00"))
                                .reasonCode("CUSTOMER_REQUEST")
                                .requestFingerprint("fp_rdrce_ref_" + uid)
                                .build());
                return "refund:ok";
            } catch (Exception e) {
                return "refund:fail:" + e.getMessage();
            }
        });

        // Thread B: dispute ₹60
        Future<String> disputeFuture = pool.submit(() -> {
            barrier.await();
            try {
                disputeService.openDispute(merchantId, paymentId,
                        DisputeCreateRequestDTO.builder()
                                .customerId(customer.getId())
                                .amount(new BigDecimal("60.00"))
                                .reasonCode("FRAUDULENT")
                                .dueBy(LocalDateTime.now().plusDays(30))
                                .build());
                return "dispute:ok";
            } catch (Exception e) {
                return "dispute:fail:" + e.getMessage();
            }
        });

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        String refundResult  = refundFuture.get();
        String disputeResult = disputeFuture.get();

        // At least one must fail — ₹50 + ₹60 = ₹110 which exceeds ₹100
        boolean refundOk  = refundResult.startsWith("refund:ok");
        boolean disputeOk = disputeResult.startsWith("dispute:ok");
        assertThat(refundOk && disputeOk)
                .as("Both refund (₹50) AND dispute (₹60) cannot both succeed on ₹100 payment — " +
                    "refundResult=" + refundResult + ", disputeResult=" + disputeResult)
                .isFalse();

        // Core invariant: refunded + disputed ≤ captured
        Payment refreshed = paymentRepository.findById(paymentId).orElseThrow();
        BigDecimal combined = refreshed.getRefundedAmount().add(refreshed.getDisputedAmount());
        assertThat(combined)
                .as("refundedAmount + disputedAmount must not exceed capturedAmount")
                .isLessThanOrEqualTo(refreshed.getCapturedAmount());

        // Minor-unit invariant
        assertThat(refreshed.getRefundedAmountMinor() + refreshed.getDisputedAmountMinor())
                .as("refunded_minor + disputed_minor must not exceed captured_minor")
                .isLessThanOrEqualTo(refreshed.getCapturedAmountMinor());
    }

    // ── 3. DB capacity constraint enforcement ────────────────────────────────

    @Test
    @DisplayName("Direct JDBC update violating chk_payment_capacity is rejected by DB")
    void paymentCapacityDbConstraint_rejectedOnDirectUpdate() {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        PaymentIntentV2 intent = paymentIntentV2Repository.save(PaymentIntentV2.builder()
                .merchant(merchant)
                .customer(customer)
                .amount(new BigDecimal("200.0000"))
                .currency("INR")
                .clientSecret("cs_dbchk_" + uid)
                .status(PaymentIntentStatusV2.SUCCEEDED)
                .build());

        // capturedAmount = 200 → capturedAmountMinor = 2_000_000
        Payment payment = paymentRepository.save(Payment.builder()
                .paymentIntentId(intent.getId())
                .merchantId(merchant.getId())
                .amount(new BigDecimal("200.00"))
                .currency("INR")
                .status(PaymentStatus.CAPTURED)
                .gatewayTxnId("gw_dbchk_" + uid)
                .capturedAmount(new BigDecimal("200.0000"))
                .capturedAmountMinor(2_000_000L)
                .build());

        // Attempt to set refunded_amount_minor > captured_amount_minor via direct SQL.
        // The chk_payment_capacity CHECK constraint must reject this.
        String sql = "UPDATE payments SET refunded_amount_minor = 9999999 WHERE id = ?";

        org.springframework.dao.DataIntegrityViolationException thrown =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> jdbcTemplate.update(sql, payment.getId()),
                        org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(thrown)
                .as("DB CHECK constraint chk_payment_capacity must reject refunded_minor > captured_minor")
                .isNotNull();
        assertThat(thrown.getMessage())
                .as("error message should reference the violated constraint")
                .containsIgnoringCase("chk_payment_capacity");
    }
}
