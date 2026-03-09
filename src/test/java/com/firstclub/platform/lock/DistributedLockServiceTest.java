package com.firstclub.platform.lock;

import com.firstclub.platform.lock.fencing.FencingTokenService;
import com.firstclub.platform.lock.metrics.LockMetricsService;
import com.firstclub.platform.lock.redis.LockOwnerIdentityProvider;
import com.firstclub.platform.lock.redis.LockScriptRegistry;
import com.firstclub.platform.redis.RedisKeyFactory;
import com.firstclub.platform.redis.RedisOpsFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DistributedLockService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DistributedLockService — Unit Tests")
class DistributedLockServiceTest {

    @Mock private RedisKeyFactory keyFactory;
    @Mock private LockOwnerIdentityProvider ownerProvider;
    @Mock private FencingTokenService fencingTokenService;
    @Mock private LockMetricsService metricsService;
    @Mock private ObjectProvider<RedisOpsFacade> redisProvider;
    @Mock private RedisOpsFacade ops;

    private LockScriptRegistry scripts;
    private DistributedLockService service;

    private static final String RESOURCE_TYPE = "subscription";
    private static final String RESOURCE_ID = "42";
    private static final String LOCK_KEY = "prod:firstclub:lock:subscription:42";
    private static final String OWNER_A = "pod-abc:10:uuid-aaa";
    private static final String OWNER_B = "pod-abc:11:uuid-bbb";
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Duration TIMEOUT = Duration.ofMillis(100);

    @BeforeEach
    void setUp() {
        scripts = new LockScriptRegistry();
        service = new DistributedLockService(scripts, keyFactory, ownerProvider,
                fencingTokenService, metricsService, redisProvider);
    }

    private void givenRedisAvailable() {
        when(redisProvider.getIfAvailable()).thenReturn(ops);
    }

    private void givenLockKeyBuilt() {
        when(keyFactory.distributedLockKey(RESOURCE_TYPE, RESOURCE_ID)).thenReturn(LOCK_KEY);
    }

    @Nested
    @DisplayName("OnlyOneCallerAcquires — mutually exclusive ownership")
    class OnlyOneCallerAcquires {

        @Test
        @DisplayName("simultaneousAcquire_onlyOneWins — ACQUIRE returns 1 then 0")
        void simultaneousAcquire_onlyOneWins() {
            givenRedisAvailable();
            givenLockKeyBuilt();
            when(ownerProvider.generateOwnerToken()).thenReturn(OWNER_A, OWNER_B);
            when(fencingTokenService.generateToken(RESOURCE_TYPE, RESOURCE_ID)).thenReturn(1L);
            doReturn(1L).doReturn(0L).when(ops).runLuaScript(any(), anyList(), any(Object[].class));

            LockAcquisitionResult resultA = service.tryAcquire(RESOURCE_TYPE, RESOURCE_ID, TTL);
            LockAcquisitionResult resultB = service.tryAcquire(RESOURCE_TYPE, RESOURCE_ID, TTL);

            assertThat(resultA.isAcquired()).isTrue();
            assertThat(resultA.lockHandle()).isPresent();
            assertThat(resultB.isAcquired()).isFalse();
            assertThat(resultB.getStatus()).isEqualTo(LockAcquisitionResult.Status.FAILED_ALREADY_LOCKED);
        }

        @Test
        @DisplayName("tryAcquire_redisUnavailable_returnsRedisUnavailableStatus")
        void tryAcquire_redisUnavailable_returnsRedisUnavailableStatus() {
            when(redisProvider.getIfAvailable()).thenReturn(null);

            LockAcquisitionResult result = service.tryAcquire(RESOURCE_TYPE, RESOURCE_ID, TTL);

            assertThat(result.isAcquired()).isFalse();
            assertThat(result.getStatus()).isEqualTo(LockAcquisitionResult.Status.FAILED_REDIS_UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("SecondCallerFails — contention handling")
    class SecondCallerFails {

        @Test
        @DisplayName("acquireWithRetry_timesOut_whenAlwaysLocked — throws LockAcquisitionTimeoutException")
        void acquireWithRetry_timesOut_whenAlwaysLocked() {
            givenRedisAvailable();
            givenLockKeyBuilt();
            when(ownerProvider.generateOwnerToken()).thenReturn(OWNER_A);
            doReturn(0L).when(ops).runLuaScript(any(), anyList(), any(Object[].class));

            assertThatThrownBy(() ->
                    service.acquireWithRetry(RESOURCE_TYPE, RESOURCE_ID, TTL, TIMEOUT))
                    .isInstanceOf(LockAcquisitionTimeoutException.class)
                    .satisfies(ex -> {
                        LockAcquisitionTimeoutException e = (LockAcquisitionTimeoutException) ex;
                        assertThat(e.getResourceType()).isEqualTo(RESOURCE_TYPE);
                        assertThat(e.getResourceId()).isEqualTo(RESOURCE_ID);
                        assertThat(e.getAttempts()).isGreaterThanOrEqualTo(1);
                    });
        }

        @Test
        @DisplayName("secondCaller_getsAlreadyLocked — ACQUIRE 0 maps to FAILED_ALREADY_LOCKED")
        void secondCaller_getsAlreadyLocked() {
            givenRedisAvailable();
            givenLockKeyBuilt();
            when(ownerProvider.generateOwnerToken()).thenReturn(OWNER_B);
            doReturn(0L).when(ops).runLuaScript(any(), anyList(), any(Object[].class));

            LockAcquisitionResult result = service.tryAcquire(RESOURCE_TYPE, RESOURCE_ID, TTL);

            assertThat(result.getStatus()).isEqualTo(LockAcquisitionResult.Status.FAILED_ALREADY_LOCKED);
            assertThat(result.lockHandle()).isEmpty();
        }
    }

    @Nested
    @DisplayName("UnlockWithWrongValue — Lua owner-check safety")
    class UnlockWithWrongValue {

        @Test
        @DisplayName("releaseByKeyAndOwner_wrongOwner_doesNotDelete — RELEASE returns 0")
        void releaseByKeyAndOwner_wrongOwner_doesNotDelete() {
            givenRedisAvailable();
            doReturn(0L).when(ops).runLuaScript(any(), anyList(), any(Object[].class));

            boolean deleted = service.releaseByKeyAndOwner(LOCK_KEY, "wrong-owner-token");

            assertThat(deleted).isFalse();
        }

        @Test
        @DisplayName("releaseByKeyAndOwner_correctOwner_returnsTrue — RELEASE returns 1")
        void releaseByKeyAndOwner_correctOwner_returnsTrue() {
            givenRedisAvailable();
            doReturn(1L).when(ops).runLuaScript(any(), anyList(), any(Object[].class));

            boolean deleted = service.releaseByKeyAndOwner(LOCK_KEY, OWNER_A);

            assertThat(deleted).isTrue();
        }

        @Test
        @DisplayName("releaseByKeyAndOwner_redisUnavailable_returnsFalse")
        void releaseByKeyAndOwner_redisUnavailable_returnsFalse() {
            when(redisProvider.getIfAvailable()).thenReturn(null);

            boolean deleted = service.releaseByKeyAndOwner(LOCK_KEY, OWNER_A);

            assertThat(deleted).isFalse();
            verifyNoInteractions(ops);
        }
    }

    @Nested
    @DisplayName("ExtendWithWrongValue — owner-safe lease renewal")
    class ExtendWithWrongValue {

        @Test
        @DisplayName("extend_wrongOwner_returnsFalse — EXTEND returns 0 on owner mismatch")
        void extend_wrongOwner_returnsFalse() {
            givenRedisAvailable();
            LockHandle staleHandle = new LockHandle(
                    RESOURCE_TYPE, RESOURCE_ID, LOCK_KEY, "stale-owner", 5L, Instant.now(), () -> {});
            doReturn(0L).when(ops).runLuaScript(any(), anyList(), any(Object[].class));

            boolean extended = service.extend(staleHandle, TTL);

            assertThat(extended).isFalse();
        }

        @Test
        @DisplayName("extend_correctOwner_returnsTrue — EXTEND returns 1")
        void extend_correctOwner_returnsTrue() {
            givenRedisAvailable();
            givenLockKeyBuilt();
            when(ownerProvider.generateOwnerToken()).thenReturn(OWNER_A);
            when(fencingTokenService.generateToken(RESOURCE_TYPE, RESOURCE_ID)).thenReturn(2L);
            doReturn(1L).doReturn(1L).when(ops).runLuaScript(any(), anyList(), any(Object[].class));

            LockHandle handle = service.tryAcquire(RESOURCE_TYPE, RESOURCE_ID, TTL)
                    .lockHandle().orElseThrow();
            boolean extended = service.extend(handle, TTL);

            assertThat(extended).isTrue();
        }

        @Test
        @DisplayName("extend_releasedHandle_returnsFalse — no Redis call for released handle")
        void extend_releasedHandle_returnsFalse() {
            LockHandle releasedHandle = new LockHandle(
                    RESOURCE_TYPE, RESOURCE_ID, LOCK_KEY, OWNER_A, 1L, Instant.now(), () -> {});
            releasedHandle.close();

            boolean extended = service.extend(releasedHandle, TTL);

            assertThat(extended).isFalse();
            verifyNoInteractions(ops);
        }
    }

    @Nested
    @DisplayName("TryWithResources — AutoCloseable contract")
    class TryWithResources {

        @Test
        @DisplayName("close_callsReleaseCallback_exactlyOnce")
        void close_callsReleaseCallback_exactlyOnce() {
            Runnable onClose = mock(Runnable.class);
            LockHandle handle = new LockHandle(
                    RESOURCE_TYPE, RESOURCE_ID, LOCK_KEY, OWNER_A, 1L, Instant.now(), onClose);

            handle.close();

            verify(onClose, times(1)).run();
            assertThat(handle.isReleased()).isTrue();
        }

        @Test
        @DisplayName("doubleClose_callsCallbackOnlyOnce — idempotency via AtomicBoolean")
        void doubleClose_callsCallbackOnlyOnce() {
            Runnable onClose = mock(Runnable.class);
            LockHandle handle = new LockHandle(
                    RESOURCE_TYPE, RESOURCE_ID, LOCK_KEY, OWNER_A, 1L, Instant.now(), onClose);

            handle.close();
            handle.close();

            verify(onClose, times(1)).run();
        }

        @Test
        @DisplayName("tryWithResources_releasesLockOnExit — close() triggers RELEASE script")
        void tryWithResources_releasesLockOnExit() {
            givenRedisAvailable();
            givenLockKeyBuilt();
            when(ownerProvider.generateOwnerToken()).thenReturn(OWNER_A);
            when(fencingTokenService.generateToken(RESOURCE_TYPE, RESOURCE_ID)).thenReturn(3L);
            doReturn(1L).doReturn(1L).when(ops).runLuaScript(any(), anyList(), any(Object[].class));

            LockHandle handle;
            try (LockHandle h = service.acquireWithRetry(RESOURCE_TYPE, RESOURCE_ID, TTL, Duration.ofSeconds(1))) {
                handle = h;
                assertThat(h.isReleased()).isFalse();
                assertThat(h.getFenceToken()).isEqualTo(3L);
            }

            assertThat(handle.isReleased()).isTrue();
            verify(ops, times(2)).runLuaScript(any(), anyList(), any(Object[].class));
        }

        @Test
        @DisplayName("acquiredHandle_carryFenceToken — fence token attached to handle")
        void acquiredHandle_carryFenceToken() {
            givenRedisAvailable();
            givenLockKeyBuilt();
            when(ownerProvider.generateOwnerToken()).thenReturn(OWNER_A);
            when(fencingTokenService.generateToken(RESOURCE_TYPE, RESOURCE_ID)).thenReturn(7L);
            doReturn(1L).when(ops).runLuaScript(any(), anyList(), any(Object[].class));

            LockAcquisitionResult result = service.tryAcquire(RESOURCE_TYPE, RESOURCE_ID, TTL);

            assertThat(result.isAcquired()).isTrue();
            LockHandle handle = result.lockHandle().orElseThrow();
            assertThat(handle.getFenceToken()).isEqualTo(7L);
            assertThat(handle.getResourceType()).isEqualTo(RESOURCE_TYPE);
            assertThat(handle.getResourceId()).isEqualTo(RESOURCE_ID);
            assertThat(handle.getAcquiredAt()).isNotNull();
        }
    }
}
