package com.firstclub.concurrency;

import com.firstclub.catalog.entity.BillingType;
import com.firstclub.catalog.entity.Price;
import com.firstclub.catalog.entity.PriceVersion;
import com.firstclub.catalog.entity.Product;
import com.firstclub.catalog.entity.ProductStatus;
import com.firstclub.catalog.repository.PriceRepository;
import com.firstclub.catalog.repository.PriceVersionRepository;
import com.firstclub.catalog.repository.ProductRepository;
import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.entity.SubscriptionV2;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import com.firstclub.subscription.service.SubscriptionV2Service;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10 — Concurrency Integration Tests: Subscription
 *
 * <p>Proves that the {@code @Version} optimistic-locking guard on
 * {@link SubscriptionV2} prevents lost updates when multiple threads
 * concurrently attempt to transition the same subscription's state.
 *
 * <p>Scenario: 10 threads simultaneously call
 * {@code cancelSubscription(merchantId, subId, false)} on the same ACTIVE
 * subscription. Exactly one should succeed; the rest should fail with an
 * optimistic-lock or duplicate-cancel exception.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Subscription Concurrency — @Version OCC guard")
class SubscriptionConcurrencyIT extends PostgresIntegrationTestBase {

    @Autowired private SubscriptionV2Service      subscriptionV2Service;
    @Autowired private SubscriptionV2Repository   subscriptionV2Repository;
    @Autowired private MerchantAccountRepository  merchantAccountRepository;
    @Autowired private CustomerRepository         customerRepository;
    @Autowired private ProductRepository          productRepository;
    @Autowired private PriceRepository            priceRepository;
    @Autowired private PriceVersionRepository     priceVersionRepository;

    // Shared for the whole class; never mutated after @BeforeAll
    private MerchantAccount merchant;
    private Customer        customer;
    private Product         product;
    private Price           price;
    private PriceVersion    priceVersion;

    @BeforeAll
    void seedSharedFixtures() {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        merchant = merchantAccountRepository.save(MerchantAccount.builder()
                .merchantCode("SCIT_" + uid)
                .legalName("Sub Concurrency Test")
                .displayName("Sub Concurrency Test")
                .supportEmail("sub_" + uid + "@test.com")
                .status(MerchantStatus.ACTIVE)
                .build());

        customer = customerRepository.save(Customer.builder()
                .merchant(merchant)
                .email("cust_" + uid + "@test.com")
                .fullName("Concurrency Customer")
                .build());

        product = productRepository.save(Product.builder()
                .merchant(merchant)
                .productCode("PROD_" + uid)
                .name("Concurrency Product")
                .status(ProductStatus.ACTIVE)
                .build());

        price = priceRepository.save(Price.builder()
                .merchant(merchant)
                .product(product)
                .priceCode("PRICE_" + uid)
                .billingType(BillingType.RECURRING)
                .currency("INR")
                .amount(new BigDecimal("100.00"))
                .build());

        priceVersion = priceVersionRepository.save(PriceVersion.builder()
                .price(price)
                .effectiveFrom(LocalDateTime.now().minusMonths(1))
                .amount(new BigDecimal("100.00"))
                .currency("INR")
                .build());
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("10 concurrent cancel requests on same ACTIVE subscription — exactly 1 succeeds via OCC")
    void concurrentCancel_exactlyOneSucceeds() throws Exception {
        // Fresh ACTIVE subscription for this test
        SubscriptionV2 sub = subscriptionV2Repository.save(SubscriptionV2.builder()
                .merchant(merchant)
                .customer(customer)
                .product(product)
                .price(price)
                .priceVersion(priceVersion)
                .status(SubscriptionStatusV2.ACTIVE)
                .billingAnchorAt(LocalDateTime.now().minusMonths(1))
                .currentPeriodStart(LocalDateTime.now().minusMonths(1))
                .build());

        final Long merchantId = merchant.getId();
        final Long subId      = sub.getId();

        int threads = 10;
        CyclicBarrier      barrier = new CyclicBarrier(threads);
        ExecutorService    pool    = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();            // all threads start simultaneously
                try {
                    subscriptionV2Service.cancelSubscription(merchantId, subId, false);
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

        // OCC guarantees exactly 1 winner; all others get an optimistic-lock or
        // already-cancelled exception.
        assertThat(successCount)
                .as("exactly one cancel should succeed via @Version OCC")
                .isEqualTo(1);

        SubscriptionV2 updated = subscriptionV2Repository.findById(subId).orElseThrow();
        assertThat(updated.getStatus())
                .as("final subscription status must be CANCELLED")
                .isEqualTo(SubscriptionStatusV2.CANCELLED);
    }

    @Test
    @DisplayName("10 concurrent pause requests on same ACTIVE subscription — exactly 1 succeeds via OCC")
    void concurrentPause_exactlyOneSucceeds() throws Exception {
        SubscriptionV2 sub = subscriptionV2Repository.save(SubscriptionV2.builder()
                .merchant(merchant)
                .customer(customer)
                .product(product)
                .price(price)
                .priceVersion(priceVersion)
                .status(SubscriptionStatusV2.ACTIVE)
                .billingAnchorAt(LocalDateTime.now().minusMonths(1))
                .currentPeriodStart(LocalDateTime.now().minusMonths(1))
                .build());

        final Long merchantId = merchant.getId();
        final Long subId      = sub.getId();

        int threads = 10;
        CyclicBarrier      barrier = new CyclicBarrier(threads);
        ExecutorService    pool    = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                try {
                    subscriptionV2Service.pauseSubscription(merchantId, subId);
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

        assertThat(successCount)
                .as("exactly one pause should succeed via @Version OCC")
                .isEqualTo(1);

        SubscriptionV2 updated = subscriptionV2Repository.findById(subId).orElseThrow();
        assertThat(updated.getStatus())
                .as("final status must be PAUSED")
                .isEqualTo(SubscriptionStatusV2.PAUSED);
    }
}
