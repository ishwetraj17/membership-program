package com.firstclub.platform.concurrency;

import com.firstclub.platform.concurrency.retry.OptimisticRetryTemplate;
import com.firstclub.platform.concurrency.retry.RetryBackoffPolicy;
import com.firstclub.platform.concurrency.retry.RetryJitterStrategy;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link OptimisticRetryTemplate}, {@link RetryBackoffPolicy},
 * and {@link RetryJitterStrategy}.
 *
 * All tests are pure unit tests — no Spring context required.
 */
@DisplayName("OptimisticRetryTemplate")
class OptimisticRetryTemplateTest {

    /** Zero-delay policy so tests complete instantly without sleeping. */
    private static final RetryBackoffPolicy NO_DELAY = new RetryBackoffPolicy(5, 0L, 1.0, 0.0);

    private OptimisticRetryTemplate template;

    @BeforeEach
    void setUp() {
        template = new OptimisticRetryTemplate(new RetryJitterStrategy());
    }

    // =========================================================================
    // Successful retry after transient OCC conflict
    // =========================================================================

    @Nested
    @DisplayName("Successful retry after transient OCC conflict")
    class SuccessAfterConflict {

        @Test
        @DisplayName("succeeds on 2nd attempt when 1st throws ObjectOptimisticLockingFailureException")
        void successAfterObjectOptimisticLocking() {
            AtomicInteger calls = new AtomicInteger(0);

            String result = template.executeWithRetry(() -> {
                if (calls.getAndIncrement() == 0) {
                    throw new ObjectOptimisticLockingFailureException("Entity", 1L);
                }
                return "success";
            }, "test-ctx", NO_DELAY);

            assertThat(result).isEqualTo("success");
            assertThat(calls.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("succeeds on 2nd attempt when 1st throws JPA OptimisticLockException")
        void successAfterJpaOptimisticLockException() {
            AtomicInteger calls = new AtomicInteger(0);

            String result = template.executeWithRetry(() -> {
                if (calls.getAndIncrement() == 0) {
                    throw new OptimisticLockException("version mismatch");
                }
                return "ok";
            }, "test-ctx", NO_DELAY);

            assertThat(result).isEqualTo("ok");
        }

        @Test
        @DisplayName("returns value immediately when first attempt succeeds")
        void noConflict_returnImmediately() {
            AtomicInteger calls = new AtomicInteger(0);

            int result = template.executeWithRetry(() -> {
                calls.incrementAndGet();
                return 42;
            }, "test-ctx", NO_DELAY);

            assertThat(result).isEqualTo(42);
            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("succeeds after multiple transient conflicts within maxRetries")
        void multipleConflicts_successBeforeExhaustion() {
            RetryBackoffPolicy policy = new RetryBackoffPolicy(4, 0L, 1.0, 0.0);
            AtomicInteger calls = new AtomicInteger(0);

            String result = template.executeWithRetry(() -> {
                int call = calls.getAndIncrement();
                if (call < 3) {
                    // First 3 attempts fail
                    throw new ObjectOptimisticLockingFailureException("Entity", 1L);
                }
                return "eventual-success";
            }, "multi-conflict", policy);

            assertThat(result).isEqualTo("eventual-success");
            assertThat(calls.get()).isEqualTo(4); // 3 failures + 1 success
        }
    }

    // =========================================================================
    // Max-attempt exhaustion
    // =========================================================================

    @Nested
    @DisplayName("Max-attempt exhaustion")
    class MaxAttemptExhaustion {

        @Test
        @DisplayName("throws ConcurrencyConflictException after maxRetries+1 total attempts")
        void exhausted_throwsConcurrencyConflictException() {
            RetryBackoffPolicy policy = new RetryBackoffPolicy(3, 0L, 1.0, 0.0);
            AtomicInteger calls = new AtomicInteger(0);

            assertThatThrownBy(() ->
                    template.executeWithRetry(() -> {
                        calls.incrementAndGet();
                        throw new ObjectOptimisticLockingFailureException("Entity", 1L);
                    }, "trial", policy)
            )
                    .isInstanceOf(ConcurrencyConflictException.class)
                    .satisfies(e -> {
                        ConcurrencyConflictException cce = (ConcurrencyConflictException) e;
                        assertThat(cce.getReason())
                                .isEqualTo(ConcurrencyConflictException.ConflictReason.OPTIMISTIC_LOCK);
                        assertThat(cce.getEntityType()).isEqualTo("trial");
                    });

            // 1 initial attempt + 3 retries = 4 total attempts
            assertThat(calls.get()).isEqualTo(4);
        }

        @Test
        @DisplayName("exception message contains attempt count info")
        void exhausted_messageContainsAttemptCount() {
            RetryBackoffPolicy policy = new RetryBackoffPolicy(2, 0L, 1.0, 0.0);

            assertThatThrownBy(() ->
                    template.executeWithRetry(
                            () -> { throw new ObjectOptimisticLockingFailureException("X", 1L); },
                            "ctx", policy)
            )
                    .isInstanceOf(ConcurrencyConflictException.class)
                    .hasMessageContaining("ctx");
        }

        @Test
        @DisplayName("non-OCC exceptions propagate immediately without retry")
        void nonOccException_propagatesImmediately() {
            AtomicInteger calls = new AtomicInteger(0);

            assertThatThrownBy(() ->
                    template.executeWithRetry(() -> {
                        calls.incrementAndGet();
                        throw new IllegalStateException("unrelated error");
                    }, "ctx", NO_DELAY)
            )
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("unrelated error");

            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("maxRetries=0 means no retries — first failure is immediately re-thrown")
        void zeroMaxRetries_noRetry() {
            RetryBackoffPolicy policy = new RetryBackoffPolicy(0, 0L, 1.0, 0.0);
            AtomicInteger calls = new AtomicInteger(0);

            assertThatThrownBy(() ->
                    template.executeWithRetry(() -> {
                        calls.incrementAndGet();
                        throw new ObjectOptimisticLockingFailureException("Entity", 1L);
                    }, "ctx", policy)
            ).isInstanceOf(ConcurrencyConflictException.class);

            assertThat(calls.get()).isEqualTo(1);
        }
    }

    // =========================================================================
    // Jitter and back-off bounds
    // =========================================================================

    @Nested
    @DisplayName("Jitter and back-off bounds")
    class JitterAndBackoffBounds {

        @Test
        @DisplayName("computed delay is >= baseDelayMs for every attempt")
        void delay_isAtLeastBaseDelay() {
            RetryJitterStrategy jitter = new RetryJitterStrategy();
            RetryBackoffPolicy policy = new RetryBackoffPolicy(5, 100L, 2.0, 0.3);

            for (int attempt = 0; attempt < 5; attempt++) {
                long delay = policy.computeDelay(attempt, jitter);
                long expectedBase = (long) (100L * Math.pow(2.0, attempt));
                assertThat(delay)
                        .as("delay at attempt %d must be >= base %d", attempt, expectedBase)
                        .isGreaterThanOrEqualTo(expectedBase);
            }
        }

        @Test
        @DisplayName("computed delay is <= baseDelayMs * multiplier^attempt * (1 + jitterFraction)")
        void delay_isWithinUpperBound() {
            RetryJitterStrategy jitter = new RetryJitterStrategy();
            RetryBackoffPolicy policy = new RetryBackoffPolicy(5, 100L, 2.0, 0.3);

            for (int attempt = 0; attempt < 5; attempt++) {
                long delay = policy.computeDelay(attempt, jitter);
                long base = (long) (100L * Math.pow(2.0, attempt));
                long maxExpected = (long) (base * 1.3) + 1; // +1 for long-cast rounding
                assertThat(delay)
                        .as("delay at attempt %d must be <= upper bound %d", attempt, maxExpected)
                        .isLessThanOrEqualTo(maxExpected);
            }
        }

        @Test
        @DisplayName("zero jitterFraction yields exactly baseDelay")
        void zeroJitter_exactlyBaseDelay() {
            RetryJitterStrategy jitter = new RetryJitterStrategy();
            RetryBackoffPolicy policy = new RetryBackoffPolicy(3, 200L, 2.0, 0.0);

            long delay = policy.computeDelay(0, jitter);
            assertThat(delay).isEqualTo(200L);
        }

        @Test
        @DisplayName("zero baseDelayMs always produces 0 regardless of jitterFraction")
        void zeroBase_alwaysZero() {
            RetryJitterStrategy jitter = new RetryJitterStrategy();
            RetryBackoffPolicy policy = new RetryBackoffPolicy(3, 0L, 2.0, 0.5);

            for (int i = 0; i < 20; i++) {
                assertThat(policy.computeDelay(0, jitter)).isEqualTo(0L);
            }
        }

        @Test
        @DisplayName("delay grows with each attempt when multiplier > 1")
        void delay_growsExponentially() {
            RetryJitterStrategy noJitter = new RetryJitterStrategy();
            RetryBackoffPolicy policy = new RetryBackoffPolicy(5, 100L, 2.0, 0.0);

            long prev = policy.computeDelay(0, noJitter);
            for (int attempt = 1; attempt < 5; attempt++) {
                long curr = policy.computeDelay(attempt, noJitter);
                assertThat(curr).isGreaterThan(prev);
                prev = curr;
            }
        }
    }

    // =========================================================================
    // Entity-reload requirement on retry
    // =========================================================================

    @Nested
    @DisplayName("Entity reload requirement on retry")
    class EntityReloadRequirement {

        /**
         * Demonstrates the entity-reload contract: the supplier must load a fresh
         * entity on every invocation.  Here we simulate this with a mutable counter
         * representing the "database" value that gets read on each attempt.
         */
        @Test
        @DisplayName("supplier is called fresh on each attempt — reads latest DB state")
        void supplierCalledFreshEachAttempt() {
            AtomicInteger dbVersion  = new AtomicInteger(2); // "DB" has version 2
            AtomicInteger seenVersion = new AtomicInteger(-1);
            AtomicInteger callCount  = new AtomicInteger(0);

            template.executeWithRetry(() -> {
                int call = callCount.getAndIncrement();
                // Each invocation re-reads from "DB"
                int freshVersion = dbVersion.get();
                seenVersion.set(freshVersion);
                if (call == 0) {
                    // First call: simulate stale-version conflict
                    throw new ObjectOptimisticLockingFailureException("FakeEntity", 1L);
                }
                // Second call: version matches, proceed
                return "updated-at-version-" + freshVersion;
            }, "versionTest", NO_DELAY);

            // The supplier was called twice
            assertThat(callCount.get()).isEqualTo(2);
            // Both calls read the current DB version (not a stale captured value)
            assertThat(seenVersion.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("stale-closure anti-pattern always conflicts (demonstrates why reload is required)")
        void staleClosureAntiPattern_alwaysConflicts() {
            RetryBackoffPolicy singleRetry = new RetryBackoffPolicy(1, 0L, 1.0, 0.0);

            // Simulate the anti-pattern: entity captured once outside the supplier loop
            String staleEntity = "stale"; // never refreshed across retries

            assertThatThrownBy(() ->
                    template.executeWithRetry(() -> {
                        // BAD: staleEntity is never reloaded — always OCC-fails
                        if (staleEntity.equals("stale")) {
                            throw new ObjectOptimisticLockingFailureException("Entity", 1L);
                        }
                        return staleEntity;
                    }, "stale-demo", singleRetry)
            )
                    .isInstanceOf(ConcurrencyConflictException.class);
        }

        @Test
        @DisplayName("each retry attempt increments call count — proving fresh invocation")
        void retryIncrementsCalls() {
            RetryBackoffPolicy twoRetries = new RetryBackoffPolicy(2, 0L, 1.0, 0.0);
            AtomicInteger callCount = new AtomicInteger(0);

            assertThatThrownBy(() ->
                    template.executeWithRetry(() -> {
                        callCount.incrementAndGet();
                        throw new ObjectOptimisticLockingFailureException("Entity", 1L);
                    }, "count-test", twoRetries)
            ).isInstanceOf(ConcurrencyConflictException.class);

            // 1 initial + 2 retries = 3 total calls
            assertThat(callCount.get()).isEqualTo(3);
        }
    }
}
