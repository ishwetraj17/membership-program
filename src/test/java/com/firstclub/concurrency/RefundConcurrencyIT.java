package com.firstclub.concurrency;

import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.membership.PostgresIntegrationTestBase;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10 — Concurrency Integration Tests: Refunds
 *
 * <p>Proves that the pessimistic write lock
 * ({@code SELECT ... FOR UPDATE} via {@code findByIdForUpdate}) on the
 * {@link Payment} row in {@code RefundServiceV2Impl} prevents over-refunding
 * under a concurrent refund storm.
 *
 * <p>Scenario: 10 threads simultaneously request a £200 refund on a CAPTURED
 * payment with {@code capturedAmount = 1000}.  All accesses are serialised by
 * the pessimistic lock, so at most 5 refunds of 200 can succeed before the
 * payment is fully refunded — no over-refund is ever possible.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Refund Concurrency — pessimistic-lock over-refund guard")
class RefundConcurrencyIT extends PostgresIntegrationTestBase {

    @Autowired private RefundServiceV2            refundServiceV2;
    @Autowired private PaymentRepository          paymentRepository;
    @Autowired private RefundV2Repository         refundV2Repository;
    @Autowired private PaymentIntentV2Repository  paymentIntentV2Repository;
    @Autowired private MerchantAccountRepository  merchantAccountRepository;
    @Autowired private CustomerRepository         customerRepository;

    // Shared fixtures; never mutated after @BeforeAll
    private MerchantAccount merchant;
    private Customer        customer;

    @BeforeAll
    void seedSharedFixtures() {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        merchant = merchantAccountRepository.save(MerchantAccount.builder()
                .merchantCode("RFCIT_" + uid)
                .legalName("Refund Concurrency Test")
                .displayName("Refund Concurrency Test")
                .supportEmail("refund_" + uid + "@test.com")
                .status(MerchantStatus.ACTIVE)
                .build());

        customer = customerRepository.save(Customer.builder()
                .merchant(merchant)
                .email("rfcust_" + uid + "@test.com")
                .fullName("Refund Concurrency Customer")
                .build());
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("10 × £200 concurrent refunds on a £1000 payment — at most 5 succeed, no over-refund")
    void concurrentRefundStorm_noOverRefund() throws Exception {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        // Parent intent (needed as FK on Payment)
        PaymentIntentV2 intent = paymentIntentV2Repository.save(PaymentIntentV2.builder()
                .merchant(merchant)
                .customer(customer)
                .amount(new BigDecimal("1000.0000"))
                .currency("INR")
                .clientSecret("cs_rf_" + uid)
                .status(PaymentIntentStatusV2.SUCCEEDED)
                .build());

        // CAPTURED payment: capturedAmount = 1000, nothing refunded yet
        Payment payment = paymentRepository.save(Payment.builder()
                .paymentIntentId(intent.getId())
                .merchantId(merchant.getId())
                .amount(new BigDecimal("1000.00"))
                .currency("INR")
                .status(PaymentStatus.CAPTURED)
                .gatewayTxnId("gw_rf_" + uid)
                .capturedAmount(new BigDecimal("1000.0000"))
                .build());  // refundedAmount / netAmount default to ZERO via @Builder.Default

        final Long merchantId = merchant.getId();
        final Long paymentId  = payment.getId();

        RefundCreateRequestDTO dto = RefundCreateRequestDTO.builder()
                .amount(new BigDecimal("200.00"))
                .reasonCode("CUSTOMER_REQUEST")
                .build();

        int threads = 10;
        CyclicBarrier      barrier = new CyclicBarrier(threads);
        ExecutorService    pool    = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();            // all threads start simultaneously
                try {
                    refundServiceV2.createRefund(merchantId, paymentId, dto);
                    return "ok";
                } catch (Exception e) {
                    return "fail:" + e.getClass().getSimpleName();
                }
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long successCount = futures.stream().filter(f -> {
            try { return "ok".equals(f.get()); } catch (Exception ex) { return false; }
        }).count();

        // Reload payment to see final refunded state
        Payment refreshed = paymentRepository.findById(paymentId).orElseThrow();

        // Core invariant: refundedAmount must never exceed capturedAmount
        assertThat(refreshed.getRefundedAmount())
                .as("refundedAmount must not exceed capturedAmount — no over-refund")
                .isLessThanOrEqualTo(refreshed.getCapturedAmount());

        // With capturedAmount=1000 and each request=200, at most 5 can succeed
        assertThat(successCount)
                .as("at most 5 refunds of 200 fit within 1000 capturedAmount")
                .isLessThanOrEqualTo(5);

        // Verify the DB reflects the same count of committed RefundV2 rows
        List<RefundV2> committedRefunds = refundV2Repository.findByPaymentId(paymentId);
        assertThat(committedRefunds).hasSizeLessThanOrEqualTo(5);

        BigDecimal sumOfRefunds = committedRefunds.stream()
                .map(RefundV2::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumOfRefunds)
                .as("sum of individual refund amounts must equal payment.refundedAmount")
                .isEqualByComparingTo(refreshed.getRefundedAmount());
    }

    @Test
    @DisplayName("Full refund + 9 concurrent over-refund attempts — none over-refund after full refund")
    void concurrentRefundAfterFullRefund_zeroSucceed() throws Exception {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        PaymentIntentV2 intent = paymentIntentV2Repository.save(PaymentIntentV2.builder()
                .merchant(merchant)
                .customer(customer)
                .amount(new BigDecimal("300.0000"))
                .currency("INR")
                .clientSecret("cs_rfull_" + uid)
                .status(PaymentIntentStatusV2.SUCCEEDED)
                .build());

        // Payment already fully refunded
        Payment payment = paymentRepository.save(Payment.builder()
                .paymentIntentId(intent.getId())
                .merchantId(merchant.getId())
                .amount(new BigDecimal("300.00"))
                .currency("INR")
                .status(PaymentStatus.REFUNDED)
                .gatewayTxnId("gw_rfull_" + uid)
                .capturedAmount(new BigDecimal("300.0000"))
                .refundedAmount(new BigDecimal("300.0000"))
                .build());

        final Long merchantId = merchant.getId();
        final Long paymentId  = payment.getId();

        RefundCreateRequestDTO dto = RefundCreateRequestDTO.builder()
                .amount(new BigDecimal("50.00"))
                .reasonCode("DUPLICATE_CHARGE")
                .build();

        int threads = 9;
        CyclicBarrier      barrier = new CyclicBarrier(threads);
        ExecutorService    pool    = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                try {
                    refundServiceV2.createRefund(merchantId, paymentId, dto);
                    return "ok";
                } catch (Exception e) {
                    return "fail:" + e.getClass().getSimpleName();
                }
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long successCount = futures.stream().filter(f -> {
            try { return "ok".equals(f.get()); } catch (Exception ex) { return false; }
        }).count();

        // Payment is REFUNDED (terminal) — no further refunds allowed
        assertThat(successCount)
                .as("no refunds allowed on a REFUNDED (terminal) payment")
                .isZero();

        Payment refreshed = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(refreshed.getRefundedAmount())
                .as("refundedAmount must not increase beyond capturedAmount")
                .isLessThanOrEqualTo(refreshed.getCapturedAmount());
    }
}
