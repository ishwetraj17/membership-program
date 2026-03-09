package com.firstclub.concurrency;

import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookDelivery;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookDeliveryStatus;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookEndpoint;
import com.firstclub.notifications.webhooks.repository.MerchantWebhookDeliveryRepository;
import com.firstclub.notifications.webhooks.repository.MerchantWebhookEndpointRepository;
import com.firstclub.notifications.webhooks.service.MerchantWebhookDeliveryService;
import com.firstclub.notifications.webhooks.service.WebhookDispatcher;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 10 — Concurrency Integration Tests: Webhook Delivery
 *
 * <p>Proves that the {@code FOR UPDATE SKIP LOCKED} guard on the
 * {@code findDueForProcessingWithSkipLocked} query prevents a webhook
 * endpoint from receiving duplicate HTTP callbacks when multiple scheduler
 * threads race to pick up the same pending delivery.
 *
 * <p>Scenario: 1 PENDING delivery is due in the past.  5 threads
 * simultaneously call {@code retryDueDeliveries()}.  Only one thread can
 * lock the delivery row; the others see an empty batch.  The mock
 * {@link WebhookDispatcher} must therefore be invoked exactly once.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Webhook Delivery Concurrency — SKIP LOCKED dedup guard")
class WebhookConcurrencyIT extends PostgresIntegrationTestBase {

    @MockitoBean
    WebhookDispatcher webhookDispatcher;

    @Autowired private MerchantWebhookDeliveryService   deliveryService;
    @Autowired private MerchantWebhookDeliveryRepository deliveryRepository;
    @Autowired private MerchantWebhookEndpointRepository endpointRepository;
    @Autowired private MerchantAccountRepository        merchantAccountRepository;

    // Shared fixture; not mutated after @BeforeAll
    private MerchantAccount merchant;

    @BeforeAll
    void seedSharedFixtures() {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        merchant = merchantAccountRepository.save(MerchantAccount.builder()
                .merchantCode("WHCIT_" + uid)
                .legalName("Webhook Concurrency Test")
                .displayName("Webhook Concurrency Test")
                .supportEmail("wh_" + uid + "@test.com")
                .status(MerchantStatus.ACTIVE)
                .build());
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("5 concurrent retryDueDeliveries() on 1 PENDING delivery — dispatched exactly once (SKIP LOCKED)")
    void concurrentRetry_singleDeliveryDispatchedOnce() throws Exception {
        Mockito.reset(webhookDispatcher);
        // Mock dispatcher to return HTTP 200 (success)
        Mockito.when(webhookDispatcher.dispatch(anyString(), anyString(), anyString(), anyString(), anyLong()))
               .thenReturn(200);

        // Seed endpoint and one PENDING delivery due in the past
        MerchantWebhookEndpoint endpoint = endpointRepository.save(
                MerchantWebhookEndpoint.builder()
                        .merchantId(merchant.getId())
                        .url("https://example.com/webhook")
                        .secret("secret_" + UUID.randomUUID())
                        .active(true)
                        .subscribedEventsJson("[\"subscription.created\"]")
                        .build());

        MerchantWebhookDelivery delivery = deliveryRepository.save(
                MerchantWebhookDelivery.builder()
                        .endpointId(endpoint.getId())
                        .eventType("subscription.created")
                        .payload("{\"event\":\"subscription.created\"}")
                        .signature("sha256=abc123")
                        .status(MerchantWebhookDeliveryStatus.PENDING)
                        .nextAttemptAt(LocalDateTime.now().minusMinutes(2))
                        .build());

        int threads = 5;
        CyclicBarrier      barrier = new CyclicBarrier(threads);
        ExecutorService    pool    = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();            // all threads start simultaneously
                deliveryService.retryDueDeliveries();
                return null;
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // Propagate any unexpected exceptions from threads
        for (Future<Void> f : futures) {
            f.get();
        }

        // SKIP LOCKED: only one thread acquired the delivery row; others saw empty batch.
        // The dispatcher must have been called exactly once.
        verify(webhookDispatcher, times(1))
                .dispatch(anyString(), anyString(), anyString(), anyString(), anyLong());

        MerchantWebhookDelivery updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus())
                .as("delivery must reach DELIVERED — not double-dispatched")
                .isEqualTo(MerchantWebhookDeliveryStatus.DELIVERED);
        assertThat(updated.getAttemptCount())
                .as("attempt count must be exactly 1")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("5 concurrent retryDueDeliveries() on 2 PENDING deliveries — each dispatched exactly once")
    void concurrentRetry_twoDeliveries_eachDispatchedOnce() throws Exception {
        Mockito.reset(webhookDispatcher);
        Mockito.when(webhookDispatcher.dispatch(anyString(), anyString(), anyString(), anyString(), anyLong()))
               .thenReturn(200);

        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        MerchantWebhookEndpoint endpoint = endpointRepository.save(
                MerchantWebhookEndpoint.builder()
                        .merchantId(merchant.getId())
                        .url("https://example.com/webhook2")
                        .secret("secret_" + uid)
                        .active(true)
                        .subscribedEventsJson("[\"payment.succeeded\"]")
                        .build());

        // Seed 2 PENDING deliveries both past due
        for (int i = 0; i < 2; i++) {
            deliveryRepository.save(MerchantWebhookDelivery.builder()
                    .endpointId(endpoint.getId())
                    .eventType("payment.succeeded")
                    .payload("{\"n\":" + i + "}")
                    .signature("sha256=xyz" + i)
                    .status(MerchantWebhookDeliveryStatus.PENDING)
                    .nextAttemptAt(LocalDateTime.now().minusMinutes(3))
                    .build());
        }

        int threads = 5;
        CyclicBarrier      barrier = new CyclicBarrier(threads);
        ExecutorService    pool    = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                deliveryService.retryDueDeliveries();
                return null;
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (Future<Void> f : futures) {
            f.get();
        }

        // 2 deliveries → dispatch called exactly 2 times in total
        verify(webhookDispatcher, times(2))
                .dispatch(anyString(), anyString(), anyString(), anyString(), anyLong());

        long deliveredCount = deliveryRepository.countByEndpointIdAndStatus(
                endpoint.getId(), MerchantWebhookDeliveryStatus.DELIVERED);
        assertThat(deliveredCount)
                .as("both deliveries must end in DELIVERED")
                .isEqualTo(2);
    }
}
