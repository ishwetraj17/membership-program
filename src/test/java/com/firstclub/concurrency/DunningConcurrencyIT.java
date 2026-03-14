package com.firstclub.concurrency;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
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
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.entity.DunningAttempt.DunningStatus;
import com.firstclub.dunning.entity.DunningPolicy;
import com.firstclub.dunning.entity.DunningTerminalStatus;
import com.firstclub.dunning.port.PaymentGatewayPort;
import com.firstclub.dunning.port.PaymentGatewayPort.ChargeResult;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import com.firstclub.dunning.repository.DunningPolicyRepository;
import com.firstclub.dunning.service.DunningServiceV2;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.entity.SubscriptionV2;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * Phase 10 — Concurrency Integration Tests: Dunning
 *
 * <p>Proves that the {@code FOR UPDATE SKIP LOCKED} guard on
 * {@code findDueForProcessingWithSkipLocked} prevents duplicate gateway
 * charge attempts when multiple scheduler threads race to pick up the same
 * SCHEDULED dunning attempt.
 *
 * <p>Scenario: 1 SCHEDULED dunning attempt is due in the past.  5 threads
 * simultaneously call {@code processDueV2Attempts()}.  Only one thread
 * can lock the attempt row; the rest see an empty batch.  The mock
 * {@link PaymentGatewayPort} must therefore be invoked exactly once.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Dunning Concurrency — SKIP LOCKED duplicate-charge guard")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class DunningConcurrencyIT extends PostgresIntegrationTestBase {

    @MockitoBean
    PaymentGatewayPort paymentGatewayPort;

    @Autowired private DunningServiceV2          dunningServiceV2;
    @Autowired private DunningAttemptRepository  dunningAttemptRepository;
    @Autowired private DunningPolicyRepository   dunningPolicyRepository;
    @Autowired private SubscriptionV2Repository  subscriptionV2Repository;
    @Autowired private InvoiceRepository         invoiceRepository;
    @Autowired private MerchantAccountRepository merchantAccountRepository;
    @Autowired private CustomerRepository        customerRepository;
    @Autowired private ProductRepository         productRepository;
    @Autowired private PriceRepository           priceRepository;
    @Autowired private PriceVersionRepository    priceVersionRepository;

    // Shared fixtures; not mutated after @BeforeAll
    private MerchantAccount merchant;
    private Customer        customer;
    private Product         product;
    private Price           price;
    private PriceVersion    priceVersion;
    private DunningPolicy   policy;

    @BeforeAll
    void seedSharedFixtures() {
        String uid = UUID.randomUUID().toString().substring(0, 8);

        merchant = merchantAccountRepository.save(MerchantAccount.builder()
                .merchantCode("DNCIT_" + uid)
                .legalName("Dunning Concurrency Test")
                .displayName("Dunning Concurrency Test")
                .supportEmail("dun_" + uid + "@test.com")
                .status(MerchantStatus.ACTIVE)
                .build());

        customer = customerRepository.save(Customer.builder()
                .merchant(merchant)
                .email("dncust_" + uid + "@test.com")
                .fullName("Dunning Customer")
                .build());

        product = productRepository.save(Product.builder()
                .merchant(merchant)
                .productCode("DNPROD_" + uid)
                .name("Dunning Product")
                .status(ProductStatus.ACTIVE)
                .build());

        price = priceRepository.save(Price.builder()
                .merchant(merchant)
                .product(product)
                .priceCode("DNPRICE_" + uid)
                .billingType(BillingType.RECURRING)
                .currency("INR")
                .amount(new BigDecimal("500.00"))
                .build());

        priceVersion = priceVersionRepository.save(PriceVersion.builder()
                .price(price)
                .effectiveFrom(LocalDateTime.now().minusMonths(2))
                .amount(new BigDecimal("500.00"))
                .currency("INR")
                .build());

        policy = dunningPolicyRepository.save(DunningPolicy.builder()
                .merchantId(merchant.getId())
                .policyCode("DEFAULT")
                .retryOffsetsJson("[1,5,10]")
                .maxAttempts(3)
                .graceDays(7)
                .statusAfterExhaustion(DunningTerminalStatus.SUSPENDED)
                .build());
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("5 concurrent processDueV2Attempts() on 1 SCHEDULED attempt — gateway charged exactly once (SKIP LOCKED)")
    void concurrentProcess_singleAttemptChargedOnce() throws Exception {
        Mockito.reset(paymentGatewayPort);
        Mockito.when(paymentGatewayPort.chargeWithCode(any())).thenReturn(ChargeResult.success());

        // PAST_DUE subscription
        SubscriptionV2 sub = subscriptionV2Repository.save(SubscriptionV2.builder()
                .merchant(merchant)
                .customer(customer)
                .product(product)
                .price(price)
                .priceVersion(priceVersion)
                .status(SubscriptionStatusV2.PAST_DUE)
                .billingAnchorAt(LocalDateTime.now().minusMonths(2))
                .currentPeriodStart(LocalDateTime.now().minusMonths(1))
                .build());

        // OPEN invoice for the subscription
        Invoice invoice = invoiceRepository.save(Invoice.builder()
                .userId(customer.getId())           // legacy userId field
                .subscriptionId(sub.getId())
                .merchantId(merchant.getId())
                .status(InvoiceStatus.OPEN)
                .currency("INR")
                .totalAmount(new BigDecimal("500.00"))
                .subtotal(new BigDecimal("500.0000"))
                .dueDate(LocalDateTime.now().minusDays(1))
                .build());

        // SCHEDULED dunning attempt due in the past
        DunningAttempt attempt = dunningAttemptRepository.save(DunningAttempt.builder()
                .subscriptionId(sub.getId())
                .invoiceId(invoice.getId())
                .attemptNumber(1)
                .scheduledAt(LocalDateTime.now().minusMinutes(2))
                .status(DunningStatus.SCHEDULED)
                .dunningPolicyId(policy.getId())
                .build());

        int threads = 5;
        CyclicBarrier      barrier = new CyclicBarrier(threads);
        ExecutorService    pool    = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();            // all threads start simultaneously
                dunningServiceV2.processDueV2Attempts();
                return null;
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (Future<Void> f : futures) {
            f.get();
        }

        // SKIP LOCKED: only one thread acquired the attempt row.
        // The payment gateway must have been charged exactly once.
        Mockito.verify(paymentGatewayPort, Mockito.times(1)).chargeWithCode(any());

        DunningAttempt updated = dunningAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(updated.getStatus())
                .as("attempt must be in a terminal state (SUCCESS or FAILED)")
                .isIn(DunningStatus.SUCCESS, DunningStatus.FAILED);
    }

    @Test
    @DisplayName("5 concurrent processDueV2Attempts() on 1 SCHEDULED attempt — charge FAILED is recorded once")
    void concurrentProcess_chargeFailure_recordedOnce() throws Exception {
        Mockito.reset(paymentGatewayPort);
        Mockito.when(paymentGatewayPort.chargeWithCode(any())).thenReturn(ChargeResult.failed("card_declined"));

        SubscriptionV2 sub = subscriptionV2Repository.save(SubscriptionV2.builder()
                .merchant(merchant)
                .customer(customer)
                .product(product)
                .price(price)
                .priceVersion(priceVersion)
                .status(SubscriptionStatusV2.PAST_DUE)
                .billingAnchorAt(LocalDateTime.now().minusMonths(2))
                .currentPeriodStart(LocalDateTime.now().minusMonths(1))
                .build());

        Invoice invoice = invoiceRepository.save(Invoice.builder()
                .userId(customer.getId())
                .subscriptionId(sub.getId())
                .merchantId(merchant.getId())
                .status(InvoiceStatus.OPEN)
                .currency("INR")
                .totalAmount(new BigDecimal("500.00"))
                .subtotal(new BigDecimal("500.0000"))
                .dueDate(LocalDateTime.now().minusDays(1))
                .build());

        DunningAttempt attempt = dunningAttemptRepository.save(DunningAttempt.builder()
                .subscriptionId(sub.getId())
                .invoiceId(invoice.getId())
                .attemptNumber(1)
                .scheduledAt(LocalDateTime.now().minusMinutes(2))
                .status(DunningStatus.SCHEDULED)
                .dunningPolicyId(policy.getId())
                .build());

        int threads = 5;
        CyclicBarrier      barrier = new CyclicBarrier(threads);
        ExecutorService    pool    = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                dunningServiceV2.processDueV2Attempts();
                return null;
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (Future<Void> f : futures) {
            f.get();
        }

        // Even on failure, charge must be attempted exactly once.
        Mockito.verify(paymentGatewayPort, Mockito.times(1)).chargeWithCode(any());

        // Attempt must be in a non-SCHEDULED terminal state
        DunningAttempt updated = dunningAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(updated.getStatus())
                .as("attempt must not remain SCHEDULED after processing")
                .isNotEqualTo(DunningStatus.SCHEDULED);
    }
}
