package com.firstclub.platform.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisKeyFactory}.
 *
 * <p>Verifies that key strings are deterministic, correctly formatted, and
 * follow the canonical pattern: {@code {env}:firstclub:{domain}:{sub}:{identifier}}.
 * No Redis connection is required — this class is a pure string factory.
 */
@DisplayName("RedisKeyFactory — Unit Tests")
class RedisKeyFactoryTest {

    private RedisKeyFactory factory;

    @BeforeEach
    void setUp() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"test"});
        factory = new RedisKeyFactory(env);
    }

    @Test
    @DisplayName("env prefix is derived from active Spring profile")
    void envPrefix_fromActiveProfile() {
        assertThat(factory.getEnv()).isEqualTo("test");
    }

    @Test
    @DisplayName("env prefix defaults to 'dev' when no active profiles")
    void envPrefix_defaultsToDevWhenNoProfiles() {
        Environment envNoProfiles = mock(Environment.class);
        when(envNoProfiles.getActiveProfiles()).thenReturn(new String[]{});
        RedisKeyFactory noProfileFactory = new RedisKeyFactory(envNoProfiles);
        assertThat(noProfileFactory.getEnv()).isEqualTo("dev");
    }

    @Nested
    @DisplayName("Idempotency keys")
    class IdempotencyKeys {

        @Test
        @DisplayName("response key has correct structure")
        void responseKey_correctStructure() {
            String key = factory.idempotencyResponseKey("merchant-1", "req-abc");
            assertThat(key).isEqualTo("test:firstclub:idem:resp:merchant-1:req-abc");
        }

        @Test
        @DisplayName("lock key has correct structure")
        void lockKey_correctStructure() {
            String key = factory.idempotencyLockKey("merchant-2", "req-xyz");
            assertThat(key).isEqualTo("test:firstclub:idem:lock:merchant-2:req-xyz");
        }

        @Test
        @DisplayName("response and lock keys for the same idempotency key are different")
        void responseAndLockKeys_areDifferent() {
            String resp = factory.idempotencyResponseKey("m1", "k1");
            String lock = factory.idempotencyLockKey("m1", "k1");
            assertThat(resp).isNotEqualTo(lock);
        }
    }

    @Nested
    @DisplayName("Rate limit keys")
    class RateLimitKeys {

        @Test
        @DisplayName("rate limit key includes merchant, api key prefix, and window")
        void rateLimitKey_includesAllSegments() {
            String key = factory.rateLimitKey("merchant-abc", "apikey12", RedisNamespaces.WINDOW_1M);
            assertThat(key)
                    .startsWith("test:firstclub:rl:apikey:")
                    .contains("merchant-abc")
                    .contains("apikey12")
                    .contains("1m");
        }
    }

    @Nested
    @DisplayName("Gateway health keys")
    class GatewayKeys {

        @Test
        @DisplayName("gateway health key uppercases the gateway name")
        void gatewayHealthKey_uppercasesName() {
            String key = factory.gatewayHealthKey("razorpay");
            assertThat(key).isEqualTo("test:firstclub:gw:health:RAZORPAY");
        }
    }

    @Nested
    @DisplayName("Feature flag keys")
    class FeatureFlagKeys {

        @Test
        @DisplayName("feature flag key contains the flag identifier")
        void featureFlagKey_containsFlagId() {
            String key = factory.featureFlagKey("enable-new-checkout");
            assertThat(key).isEqualTo("test:firstclub:flag:enable-new-checkout");
        }
    }

    @Nested
    @DisplayName("Scheduler lock keys")
    class SchedulerKeys {

        @Test
        @DisplayName("scheduler lock key includes job name")
        void schedulerLockKey_includesJobName() {
            String key = factory.schedulerLockKey("daily-recon");
            assertThat(key).isEqualTo("test:firstclub:scheduler:lock:daily-recon");
        }
    }

    @Nested
    @DisplayName("Subscription keys")
    class SubscriptionKeys {

        @Test
        @DisplayName("subscription lock key has correct format")
        void subscriptionLockKey_correctFormat() {
            String key = factory.subscriptionLockKey("sub-12345");
            assertThat(key).isEqualTo("test:firstclub:sub:lock:sub-12345");
        }
    }

    @Nested
    @DisplayName("Ledger and Recon keys")
    class LedgerReconKeys {

        @Test
        @DisplayName("ledger balance cache key includes account and date")
        void ledgerBalanceCacheKey_correctFormat() {
            String key = factory.ledgerBalanceCacheKey("acc-001", "2024-01-15");
            assertThat(key).isEqualTo("test:firstclub:ledger:balance:acc-001:2024-01-15");
        }

        @Test
        @DisplayName("recon result cache key includes date")
        void reconResultCacheKey_correctFormat() {
            String key = factory.reconResultCacheKey("2024-01-15");
            assertThat(key).isEqualTo("test:firstclub:recon:result:2024-01-15");
        }

        @Test
        @DisplayName("recon job lock key is different from recon result key")
        void reconJobLockKey_differentFromResultKey() {
            String lock = factory.reconJobLockKey("2024-01-15");
            String result = factory.reconResultCacheKey("2024-01-15");
            assertThat(lock).isNotEqualTo(result);
        }
    }

    @Nested
    @DisplayName("Key format invariants")
    class KeyFormatInvariants {

        @Test
        @DisplayName("all keys start with the environment prefix")
        void allKeys_startWithEnvPrefix() {
            assertThat(factory.idempotencyResponseKey("m", "k")).startsWith("test:");
            assertThat(factory.rateLimitKey("m", "k", "1m")).startsWith("test:");
            assertThat(factory.gatewayHealthKey("gw")).startsWith("test:");
            assertThat(factory.featureFlagKey("flag")).startsWith("test:");
            assertThat(factory.schedulerLockKey("job")).startsWith("test:");
            assertThat(factory.ledgerBalanceCacheKey("acc", "date")).startsWith("test:");
        }

        @Test
        @DisplayName("all keys contain the app namespace 'firstclub'")
        void allKeys_containAppNamespace() {
            assertThat(factory.idempotencyResponseKey("m", "k")).contains(":firstclub:");
            assertThat(factory.rateLimitKey("m", "k", "1m")).contains(":firstclub:");
            assertThat(factory.ledgerBalanceCacheKey("acc", "date")).contains(":firstclub:");
        }

        @Test
        @DisplayName("keys from different domains do not collide")
        void keys_noCrossDomainCollision() {
            String idemKey = factory.idempotencyLockKey("entity-1", "val-1");
            String schedulerKey = factory.schedulerLockKey("entity-1");
            String subKey = factory.subscriptionLockKey("entity-1");

            assertThat(idemKey).isNotEqualTo(schedulerKey);
            assertThat(idemKey).isNotEqualTo(subKey);
            assertThat(schedulerKey).isNotEqualTo(subKey);
        }

        @Test
        @DisplayName("Phase 2 keys all start with env prefix and contain 'firstclub'")
        void phase2Keys_allStartWithEnvPrefixAndContainFirstclub() {
            assertThat(factory.distributedLockKey("sub", "1")).startsWith("test:").contains(":firstclub:");
            assertThat(factory.fenceKey("sub", "1")).startsWith("test:").contains(":firstclub:");
            assertThat(factory.rateLimitEndpointKey("42", "payment")).startsWith("test:").contains(":firstclub:");
            assertThat(factory.workerLeaseKey("evt-1")).startsWith("test:").contains(":firstclub:");
            assertThat(factory.projectionKpiCacheKey("42", "2026-01-01")).startsWith("test:").contains(":firstclub:");
        }
    }

    @Nested
    @DisplayName("Phase 2: Distributed lock keys")
    class DistributedLockKeys {

        @Test
        @DisplayName("distributedLockKey has lock domain and entity segments")
        void distributedLockKey_correctFormat() {
            String key = factory.distributedLockKey("subscription", "991");
            assertThat(key).isEqualTo("test:firstclub:lock:subscription:991");
        }

        @Test
        @DisplayName("distributedLockKey for different entity types do not collide")
        void distributedLockKey_noCrossEntityCollision() {
            String subLock = factory.distributedLockKey("subscription", "1");
            String payLock = factory.distributedLockKey("payment", "1");
            assertThat(subLock).isNotEqualTo(payLock);
        }

        @Test
        @DisplayName("distributedLockKey and idempotencyLockKey do not collide for same id")
        void distributedLockKey_differentFromIdempotencyLock() {
            String distLock = factory.distributedLockKey("subscription", "42");
            String idemLock = factory.idempotencyLockKey("42", "subscription");
            assertThat(distLock).isNotEqualTo(idemLock);
        }
    }

    @Nested
    @DisplayName("Phase 2: Fence token keys")
    class FenceTokenKeys {

        @Test
        @DisplayName("fenceKey has fence domain and entity segments")
        void fenceKey_correctFormat() {
            String key = factory.fenceKey("subscription", "991");
            assertThat(key).isEqualTo("test:firstclub:fence:subscription:991");
        }

        @Test
        @DisplayName("fenceKey and distributedLockKey are different for same entity")
        void fenceKey_differentFromDistributedLock() {
            String fence = factory.fenceKey("subscription", "991");
            String lock = factory.distributedLockKey("subscription", "991");
            assertThat(fence).isNotEqualTo(lock);
        }
    }

    @Nested
    @DisplayName("Phase 2: Endpoint rate-limit keys")
    class EndpointRateLimitKeys {

        @Test
        @DisplayName("rateLimitEndpointKey includes merchant and endpoint slug")
        void rateLimitEndpointKey_correctFormat() {
            String key = factory.rateLimitEndpointKey("42", "payment_capture");
            assertThat(key)
                    .startsWith("test:firstclub:rl:")
                    .contains("42")
                    .contains("endpoint")
                    .contains("payment_capture");
        }

        @Test
        @DisplayName("rateLimitEndpointKey differs from generic rateLimitKey for same merchant")
        void rateLimitEndpointKey_differentFromGenericRateLimit() {
            String endpoint = factory.rateLimitEndpointKey("42", "payment_capture");
            String generic = factory.rateLimitKey("42", "apikey12", "1m");
            assertThat(endpoint).isNotEqualTo(generic);
        }
    }

    @Nested
    @DisplayName("Phase 2: Worker lease keys")
    class WorkerLeaseKeys {

        @Test
        @DisplayName("workerLeaseKey has worker:outbox:lease:event segments")
        void workerLeaseKey_correctFormat() {
            String key = factory.workerLeaseKey("12345");
            assertThat(key).isEqualTo("test:firstclub:worker:outbox:lease:event:12345");
        }

        @Test
        @DisplayName("workerLeaseKey differs from outboxProcessedKey for same eventId")
        void workerLeaseKey_differentFromOutboxProcessedKey() {
            String lease = factory.workerLeaseKey("evt-001");
            String processed = factory.outboxProcessedKey("evt-001");
            assertThat(lease).isNotEqualTo(processed);
        }
    }

    @Nested
    @DisplayName("Phase 2: Projection KPI cache keys")
    class ProjectionKpiCacheKeys {

        @Test
        @DisplayName("projectionKpiCacheKey has cache:projection segments with merchant and date")
        void projectionKpiCacheKey_correctFormat() {
            String key = factory.projectionKpiCacheKey("42", "2026-03-09");
            assertThat(key).isEqualTo("test:firstclub:cache:projection:42:kpi:2026-03-09");
        }

        @Test
        @DisplayName("projectionKpiCacheKey for different dates do not collide")
        void projectionKpiCacheKey_differentDates_neverCollide() {
            String day1 = factory.projectionKpiCacheKey("42", "2026-03-09");
            String day2 = factory.projectionKpiCacheKey("42", "2026-03-10");
            assertThat(day1).isNotEqualTo(day2);
        }

        @Test
        @DisplayName("projectionKpiCacheKey and projSubStatusKey are different domains")
        void projectionKpiCacheKey_differentFromProjSubStatus() {
            String kpi = factory.projectionKpiCacheKey("42", "2026-03-09");
            String subStatus = factory.projSubStatusKey("42", "sub-001");
            assertThat(kpi).isNotEqualTo(subStatus);
        }
    }
}
