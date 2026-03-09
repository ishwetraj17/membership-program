package com.firstclub.membership.idempotency;

import com.firstclub.platform.idempotency.IdempotencyProcessingMarker;
import com.firstclub.platform.idempotency.IdempotencyResponseEnvelope;
import com.firstclub.platform.idempotency.RedisIdempotencyStore;
import com.firstclub.platform.redis.RedisAvailabilityService;
import com.firstclub.platform.redis.RedisJsonCodec;
import com.firstclub.platform.redis.RedisKeyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisIdempotencyStore}.
 *
 * <p>Tests cover both the enabled (Redis available) and disabled paths.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisIdempotencyStore tests")
class RedisIdempotencyStoreTest {

    private static final String MERCHANT    = "merchant-abc";
    private static final String KEY         = "idem-key-001";
    private static final String REDIS_KEY   = "dev:firstclub:idem:resp:merchant-abc:idem-key-001";
    private static final String LOCK_KEY    = "dev:firstclub:idem:lock:merchant-abc:idem-key-001";

    @Mock private ObjectProvider<StringRedisTemplate> templateProvider;
    @Mock private StringRedisTemplate                 redisTemplate;
    @Mock private ValueOperations<String, String>     valueOps;
    @Mock private RedisKeyFactory                     keyFactory;
    @Mock private RedisJsonCodec                      codec;
    @Mock private RedisAvailabilityService            availabilityService;

    private RedisIdempotencyStore store;

    @BeforeEach
    void setUp() {
        store = new RedisIdempotencyStore(templateProvider, keyFactory, codec, availabilityService);
        // lenient: only used by EnabledRedisTests sub-class
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ── Redis disabled ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("When Redis is disabled")
    class DisabledRedisTests {

        @BeforeEach
        void disableRedis() {
            lenient().when(availabilityService.isAvailable()).thenReturn(false);
        }

        @Test
        @DisplayName("tryGetCachedResponse returns empty without touching Redis")
        void tryGetCachedResponse_disabled_returnsEmpty() {
            Optional<IdempotencyResponseEnvelope> result =
                    store.tryGetCachedResponse(MERCHANT, KEY);
            assertThat(result).isEmpty();
            verify(templateProvider, never()).getIfAvailable();
        }

        @Test
        @DisplayName("tryAcquireLock returns false without touching Redis")
        void tryAcquireLock_disabled_returnsFalse() {
            IdempotencyProcessingMarker marker =
                    new IdempotencyProcessingMarker("hash", "POST:/api/v2/subscriptions",
                            "2025-01-01T00:00:00", "req-1");
            boolean result = store.tryAcquireLock(MERCHANT, KEY, marker);
            assertThat(result).isFalse();
            verify(templateProvider, never()).getIfAvailable();
        }

        @Test
        @DisplayName("isEnabled returns false")
        void isEnabled_disabled_returnsFalse() {
            assertThat(store.isEnabled()).isFalse();
        }
    }

    // ── Redis enabled ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("When Redis is enabled")
    class EnabledRedisTests {

        @BeforeEach
        void enableRedis() {
            lenient().when(availabilityService.isAvailable()).thenReturn(true);
            lenient().when(templateProvider.getIfAvailable()).thenReturn(redisTemplate);
            lenient().when(keyFactory.idempotencyResponseKey(MERCHANT, KEY)).thenReturn(REDIS_KEY);
            lenient().when(keyFactory.idempotencyLockKey(MERCHANT, KEY)).thenReturn(LOCK_KEY);
        }

        @Test
        @DisplayName("tryGetCachedResponse returns empty on Redis MISS")
        void tryGetCachedResponse_miss_returnsEmpty() {
            when(valueOps.get(REDIS_KEY)).thenReturn(null);
            Optional<IdempotencyResponseEnvelope> result =
                    store.tryGetCachedResponse(MERCHANT, KEY);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("tryGetCachedResponse returns envelope on Redis HIT")
        void tryGetCachedResponse_hit_returnsEnvelope() {
            String json = "{\"requestHash\":\"abc\"}";
            IdempotencyResponseEnvelope envelope =
                    new IdempotencyResponseEnvelope("abc", "POST:/api/v2/subs",
                            201, "{}", "application/json");
            when(valueOps.get(REDIS_KEY)).thenReturn(json);
            when(codec.tryFromJson(json, IdempotencyResponseEnvelope.class))
                    .thenReturn(Optional.of(envelope));

            Optional<IdempotencyResponseEnvelope> result =
                    store.tryGetCachedResponse(MERCHANT, KEY);
            assertThat(result).contains(envelope);
        }

        @Test
        @DisplayName("tryAcquireLock returns true when NX succeeds")
        void tryAcquireLock_nx_success_returnsTrue() {
            IdempotencyProcessingMarker marker =
                    new IdempotencyProcessingMarker("hash", "POST:/api/v2/subs",
                            "2025-01-01T00:00:00", "req-1");
            String markerJson = "{\"requestHash\":\"hash\"}";
            when(codec.toJson(marker)).thenReturn(markerJson);
            when(valueOps.setIfAbsent(eq(LOCK_KEY), eq(markerJson),
                    any(Duration.class))).thenReturn(true);

            boolean acquired = store.tryAcquireLock(MERCHANT, KEY, marker);
            assertThat(acquired).isTrue();
        }

        @Test
        @DisplayName("tryAcquireLock returns false when lock is already held")
        void tryAcquireLock_alreadyHeld_returnsFalse() {
            IdempotencyProcessingMarker marker =
                    new IdempotencyProcessingMarker("hash", "POST:/api/v2/subs",
                            "2025-01-01T00:00:00", "req-2");
            when(codec.toJson(marker)).thenReturn("{}");
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(false);

            boolean acquired = store.tryAcquireLock(MERCHANT, KEY, marker);
            assertThat(acquired).isFalse();
        }

        @Test
        @DisplayName("releaseLock deletes the lock key")
        void releaseLock_deletesKey() {
            store.releaseLock(MERCHANT, KEY);
            verify(redisTemplate).delete(LOCK_KEY);
        }

        @Test
        @DisplayName("cacheResponse stores JSON with correct TTL")
        void cacheResponse_storesWithTtl() {
            IdempotencyResponseEnvelope env =
                    new IdempotencyResponseEnvelope("h", "POST:/subs", 201, "{}", "application/json");
            when(codec.toJson(env)).thenReturn("{\"statusCode\":201}");

            store.cacheResponse(MERCHANT, KEY, env, 86400L);

            verify(valueOps).set(eq(REDIS_KEY), eq("{\"statusCode\":201}"),
                    eq(Duration.ofSeconds(86400L)));
        }
    }
}
