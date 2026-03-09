package com.firstclub.concurrency;

import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.payments.service.PaymentIntentV2Service;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10 — Concurrency Integration Tests: PaymentIntentV2
 *
 * <p>Proves that the {@code @Version} optimistic-locking guard on
 * {@link PaymentIntentV2} prevents duplicate state transitions when multiple
 * threads concurrently attempt to cancel the same intent.
 *
 * <p>Scenario: 10 threads simultaneously call
 * {@code cancelPaymentIntent(merchantId, intentId)} on the same intent in
 * {@code REQUIRES_PAYMENT_METHOD} state.  Exactly one should succeed;
 * the rest should fail with an optimistic-lock or invalid-transition exception.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Payment Intent Concurrency — @Version OCC guard")
class PaymentConcurrencyIT extends PostgresIntegrationTestBase {

    @Autowired private PaymentIntentV2Service     paymentIntentV2Service;
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
                .merchantCode("PCIT_" + uid)
                .legalName("Payment Concurrency Test")
                .displayName("Payment Concurrency Test")
                .supportEmail("payment_" + uid + "@test.com")
                .status(MerchantStatus.ACTIVE)
                .build());

        customer = customerRepository.save(Customer.builder()
                .merchant(merchant)
                .email("pcust_" + uid + "@test.com")
                .fullName("Payment Concurrency Customer")
                .build());
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("10 concurrent cancel requests on same REQUIRES_PAYMENT_METHOD intent — exactly 1 succeeds via OCC")
    void concurrentCancel_exactlyOneSucceeds() throws Exception {
        // Fresh intent for this test — REQUIRES_PAYMENT_METHOD allows cancellation
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        PaymentIntentV2 intent = paymentIntentV2Repository.save(PaymentIntentV2.builder()
                .merchant(merchant)
                .customer(customer)
                .amount(new BigDecimal("500.0000"))
                .currency("INR")
                .clientSecret("cs_conc_" + uid)
                .build());  // status defaults to REQUIRES_PAYMENT_METHOD

        final Long merchantId = merchant.getId();
        final Long intentId   = intent.getId();

        int threads = 10;
        CyclicBarrier      barrier = new CyclicBarrier(threads);
        ExecutorService    pool    = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();            // all threads start simultaneously
                try {
                    paymentIntentV2Service.cancelPaymentIntent(merchantId, intentId);
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

        // OCC @Version: exactly one thread commits the state transition;
        // the rest encounter a stale-entity or invalid-transition exception.
        assertThat(successCount)
                .as("exactly one cancel should succeed via @Version OCC")
                .isEqualTo(1);

        PaymentIntentV2 updated = paymentIntentV2Repository.findById(intentId).orElseThrow();
        assertThat(updated.getStatus())
                .as("intent must reach CANCELLED")
                .isEqualTo(PaymentIntentStatusV2.CANCELLED);
    }

    @Test
    @DisplayName("10 concurrent cancel requests on already-CANCELLED intent — all fail gracefully (idempotent safety)")
    void concurrentCancelAlreadyCancelled_allFail() throws Exception {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        // Seed an intent that is already CANCELLED
        PaymentIntentV2 intent = paymentIntentV2Repository.save(PaymentIntentV2.builder()
                .merchant(merchant)
                .customer(customer)
                .amount(new BigDecimal("250.0000"))
                .currency("INR")
                .clientSecret("cs_already_" + uid)
                .status(PaymentIntentStatusV2.CANCELLED)
                .build());

        final Long merchantId = merchant.getId();
        final Long intentId   = intent.getId();

        int threads = 10;
        CyclicBarrier      barrier = new CyclicBarrier(threads);
        ExecutorService    pool    = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                try {
                    paymentIntentV2Service.cancelPaymentIntent(merchantId, intentId);
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

        // CANCELLED terminal state must reject every cancel attempt
        assertThat(successCount)
                .as("no cancel should succeed on an already-CANCELLED intent")
                .isZero();

        // Status must remain CANCELLED — no side effects
        PaymentIntentV2 final_ = paymentIntentV2Repository.findById(intentId).orElseThrow();
        assertThat(final_.getStatus()).isEqualTo(PaymentIntentStatusV2.CANCELLED);
    }
}
