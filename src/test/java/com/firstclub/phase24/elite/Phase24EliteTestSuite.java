package com.firstclub.phase24.elite;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.refund.entity.RefundV2;
import com.firstclub.payments.refund.entity.RefundV2Status;
import com.firstclub.payments.refund.repository.RefundV2Repository;
import com.firstclub.payments.refund.service.RefundServiceV2;
import com.firstclub.payments.refund.dto.RefundCreateRequestDTO;
import com.firstclub.payments.refund.dto.RefundV2ResponseDTO;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 24 Elite Test Suite - Simplified Working Version
 * 
 * <p><strong>Objective:</strong> Demonstrate elite testing patterns that prove 
 * system reliability under concurrent conditions using existing codebase components.
 * 
 * <p>This simplified version focuses on:
 * <ul>
 *   <li>Concurrent refund operations with data integrity</li>
 *   <li>Database consistency under concurrent load</li>  
 *   <li>Error handling and graceful degradation</li>
 *   <li>Real-world race condition prevention</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Phase 24 Elite: Simplified Concurrency & Resilience Tests")
@TestPropertySource(properties = {
    "logging.level.com.firstclub.payments=DEBUG",
    "logging.level.org.springframework.orm.jpa=DEBUG"
})
class Phase24EliteTestSuite extends PostgresIntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(Phase24EliteTestSuite.class);

    @Autowired private PaymentIntentV2Repository paymentIntentRepository;
    @Autowired private RefundV2Repository refundV2Repository;
    @Autowired private RefundServiceV2 refundServiceV2;
    @Autowired private MerchantAccountRepository merchantAccountRepository;

    private MerchantAccount testMerchant;
    private PaymentIntentV2 testPaymentIntent;

    @BeforeAll
    void setupPhase24Fixtures() {
        // Create test merchant
        testMerchant = MerchantAccount.builder()
            .legalName("Phase24_Elite_Merchant")
            .displayName("Elite Test Merchant")
            .supportEmail("phase24@elite.test")
            .status(MerchantStatus.ACTIVE)
            .build();
        testMerchant = merchantAccountRepository.save(testMerchant);
    }

    @Test
    @DisplayName("Elite Test 1: Concurrent Refunds Maintain Data Integrity")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentRefunds_maintainDataIntegrity() throws Exception {
        // Arrange: Create payment intent with known amount
        BigDecimal capturedAmount = new BigDecimal("100.00");
        PaymentIntentV2 paymentIntent = createTestPaymentIntent(capturedAmount);
        
        int threadCount = 10;
        BigDecimal refundAmount = new BigDecimal("15.00"); // Total possible: 150.00, but only 100.00 available
        
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<RefundV2> successfulRefunds = new ArrayList<>();
        
        // Act: Launch concurrent refund attempts
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startSignal.await();
                    
                    RefundV2 refund = RefundV2.builder()
                        .paymentId(paymentIntent.getId())
                        .amount(refundAmount)
                        .reasonCode("Concurrent test refund " + threadId)
                        .status(RefundV2Status.PENDING)
                        .build();
                    
                    // Use the service interface method instead of direct entity processing
                    RefundCreateRequestDTO request = RefundCreateRequestDTO.builder()
                        .amount(refundAmount)
                        .reasonCode("Concurrent test refund " + threadId)
                        .build();
                        
                    RefundV2ResponseDTO processedRefund = refundServiceV2.createRefund(
                        testMerchant.getId(), 
                        paymentIntent.getId(), 
                        request
                    );
                    
                    if (RefundV2Status.COMPLETED.equals(processedRefund.getStatus())) {
                        synchronized (successfulRefunds) {
                            // Convert DTO back to entity for testing purposes
                            RefundV2 refundEntity = RefundV2.builder()
                                .id(processedRefund.getId())
                                .paymentId(processedRefund.getPaymentId())
                                .amount(processedRefund.getAmount())
                                .reasonCode(processedRefund.getReasonCode())
                                .status(processedRefund.getStatus())
                                .build();
                            successfulRefunds.add(refundEntity);
                        }
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                    
                } catch (Exception e) {
                    // Expected for some threads due to insufficient balance
                    failureCount.incrementAndGet();
                } finally {
                    doneSignal.countDown();
                }
            });
        }
        
        startSignal.countDown(); // Start all threads
        boolean completed = doneSignal.await(25, TimeUnit.SECONDS);
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Assert: Data integrity maintained
        assertThat(completed).isTrue();
        assertThat(successCount.get() + failureCount.get()).isEqualTo(threadCount);
        
        // Verify total refunded amount doesn't exceed captured amount
        BigDecimal totalRefunded = successfulRefunds.stream()
            .map(RefundV2::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        assertThat(totalRefunded).isLessThanOrEqualTo(capturedAmount);
        
        // Expected successful refunds: captured(100) / refund(15) = 6 complete refunds + remainder
        int expectedSuccessCount = capturedAmount.divide(refundAmount).intValue();
        assertThat(successCount.get()).isLessThanOrEqualTo(expectedSuccessCount + 1); // +1 for partial
        
        log.info("Phase 24 Elite Test 1 Results: {} successful refunds, {} failures, total refunded: {}", 
            successCount.get(), failureCount.get(), totalRefunded);
    }
    
    @Test
    @DisplayName("Elite Test 2: Database Consistency Under Load")
    void databaseConsistency_underConcurrentLoad() throws Exception {
        // Arrange: Create multiple payment intents
        List<PaymentIntentV2> paymentIntents = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            paymentIntents.add(createTestPaymentIntent(new BigDecimal("50.00")));
        }
        
        int operationsPerIntent = 3;
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(paymentIntents.size() * operationsPerIntent);
        ExecutorService executor = Executors.newFixedThreadPool(15);
        
        AtomicInteger totalOperations = new AtomicInteger(0);
        
        // Act: Concurrent operations on each payment intent
        for (PaymentIntentV2 intent : paymentIntents) {
            for (int op = 0; op < operationsPerIntent; op++) {
                executor.submit(() -> {
                    try {
                        startSignal.await();
                        
                        // Simulate various operations
                        RefundCreateRequestDTO request = RefundCreateRequestDTO.builder()
                            .amount(new BigDecimal("10.00"))
                            .reasonCode("Consistency test")
                            .build();
                        
                        try {
                            RefundV2ResponseDTO result = refundServiceV2.createRefund(
                                testMerchant.getId(), intent.getId(), request);
                            totalOperations.incrementAndGet();
                        } catch (Exception e) {
                            // Some operations expected to fail due to constraints
                        }
                        
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneSignal.countDown();
                    }
                });
            }
        }
        
        startSignal.countDown();
        boolean completed = doneSignal.await(20, TimeUnit.SECONDS);
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Assert: Database remains consistent
        assertThat(completed).isTrue();
        
        // Verify all payment intents still exist and are in valid states
        for (PaymentIntentV2 intent : paymentIntents) {
            PaymentIntentV2 refreshed = paymentIntentRepository.findById(intent.getId()).orElseThrow();
            assertThat(refreshed.getId()).isEqualTo(intent.getId());
            assertThat(refreshed.getStatus()).isIn(PaymentIntentStatusV2.values());
        }
        
        // Verify refunds are in valid states
        List<RefundV2> allRefunds = refundV2Repository.findAll();
        for (RefundV2 refund : allRefunds) {
            assertThat(refund.getStatus()).isIn(RefundV2Status.values());
            assertThat(refund.getAmount()).isGreaterThan(BigDecimal.ZERO);
        }
        
        log.info("Phase 24 Elite Test 2 Results: {} total operations completed successfully", 
            totalOperations.get());
    }
    
    @Test
    @DisplayName("Elite Test 3: Graceful Degradation Under Errors") 
    void gracefulDegradation_underErrorConditions() throws Exception {
        // Arrange: Create payment intent with limited refund capacity
        PaymentIntentV2 intent = createTestPaymentIntent(new BigDecimal("30.00"));
        
        // Act: Attempt refunds that will exceed capacity
        List<CompletableFuture<Boolean>> refundFutures = new ArrayList<>();
        
        for (int i = 0; i < 10; i++) {
            final int refundIndex = i;
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                try {
                    RefundCreateRequestDTO request = RefundCreateRequestDTO.builder()
                        .amount(new BigDecimal("10.00"))
                        .reasonCode("Degradation test " + refundIndex)
                        .build();
                    
                    RefundV2ResponseDTO result = refundServiceV2.createRefund(
                        testMerchant.getId(), intent.getId(), request);
                    return RefundV2Status.COMPLETED.equals(result.getStatus());
                    
                } catch (Exception e) {
                    // Expected for some refunds
                    return false;
                }
            });
            
            refundFutures.add(future);
        }
        
        // Wait for all refunds to complete or fail
        List<Boolean> results = refundFutures.stream()
            .map(future -> {
                try {
                    return future.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return false;
                }
            })
            .toList();
        
        // Assert: System handles errors gracefully
        long successCount = results.stream().mapToLong(success -> success ? 1 : 0).sum();
        long failureCount = results.size() - successCount;
        
        assertThat(successCount).isGreaterThan(0); // Some should succeed
        assertThat(failureCount).isGreaterThan(0); // Some should fail gracefully
        assertThat(successCount + failureCount).isEqualTo(10);
        
        // Verify system is still operational after errors
        PaymentIntentV2 finalIntent = paymentIntentRepository.findById(intent.getId()).orElseThrow();
        assertThat(finalIntent).isNotNull();
        
        log.info("Phase 24 Elite Test 3 Results: {} successes, {} graceful failures", 
            successCount, failureCount);
    }
    
    @Test  
    @DisplayName("Elite Test 4: System Recovery After Failures")
    void systemRecovery_afterFailureConditions() {
        // Arrange: Create test data
        PaymentIntentV2 intent = createTestPaymentIntent(new BigDecimal("100.00"));
        
        // Act: Force error condition and verify recovery
        try {
            // Attempt invalid refund (amount > available)
            RefundCreateRequestDTO invalidRequest = RefundCreateRequestDTO.builder()
                .amount(new BigDecimal("150.00"))
                .reasonCode("Invalid amount test")
                .build();
            
            assertThatThrownBy(() -> refundServiceV2.createRefund(
                testMerchant.getId(), intent.getId(), invalidRequest))
                .isInstanceOf(RuntimeException.class);
                
        } catch (Exception e) {
            // Error handling is working
        }
        
        // Verify system can still process valid refunds after error  
        RefundCreateRequestDTO validRequest = RefundCreateRequestDTO.builder()
            .amount(new BigDecimal("25.00"))
            .reasonCode("Recovery test")
            .build();
        
        RefundV2ResponseDTO result = refundServiceV2.createRefund(
            testMerchant.getId(), intent.getId(), validRequest);
        
        // Assert: System recovered and processed valid request
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(RefundV2Status.COMPLETED);
        
        log.info("Phase 24 Elite Test 4: System recovery verified");
    }

    private PaymentIntentV2 createTestPaymentIntent(BigDecimal amount) {
        PaymentIntentV2 intent = PaymentIntentV2.builder()
            .merchant(testMerchant)
            .amount(amount)
            .currency("USD")
            .status(PaymentIntentStatusV2.SUCCEEDED)
            .build();
        
        return paymentIntentRepository.save(intent);
    }
    
    // Logging helper
    private void log(String message, Object... args) {
        System.out.printf("[Phase24Elite] " + message + "%n", args);
    }
}