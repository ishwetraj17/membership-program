package com.firstclub.membership.ratelimit;

import com.firstclub.platform.ratelimit.RateLimitDecision;
import com.firstclub.platform.ratelimit.RateLimitPolicy;
import com.firstclub.platform.ratelimit.RateLimitProperties;
import com.firstclub.platform.ratelimit.RedisSlidingWindowRateLimiter;
import com.firstclub.platform.ratelimit.RateLimitEventRepository;
import com.firstclub.platform.redis.RedisAvailabilityService;
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
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RedisSlidingWindowRateLimiter}.
 *
 * <p>All interactions with {@link StringRedisTemplate} and the DB are mocked so
 * that tests run without a real Redis or PostgreSQL instance.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisSlidingWindowRateLimiter tests")
class RedisSlidingWindowRateLimiterTest {

    @Mock private ObjectProvider<StringRedisTemplate> templateProvider;
    @Mock private StringRedisTemplate                  redisTemplate;
    @Mock private RedisKeyFactory                      keyFactory;
    @Mock private RateLimitProperties                  properties;
    @Mock private RedisAvailabilityService             availabilityService;
    @Mock private RateLimitEventRepository             eventRepository;

    private RedisSlidingWindowRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        lenient().when(properties.resolveLimit(any())).thenAnswer(inv ->
                ((RateLimitPolicy) inv.getArgument(0)).getDefaultLimit());
        lenient().when(properties.resolveWindow(any())).thenAnswer(inv ->
                ((RateLimitPolicy) inv.getArgument(0)).getDefaultWindow());
        lenient().when(properties.isEnabled()).thenReturn(true);

        lenient().when(keyFactory.rateLimitAuthIpKey(anyString()))
                .thenReturn("dev:firstclub:rl:auth:ip:127.0.0.1");
        lenient().when(keyFactory.rateLimitAuthEmailKey(anyString()))
                .thenReturn("dev:firstclub:rl:auth:user:test@example.com");
        lenient().when(keyFactory.rateLimitPayConfirmKey(anyString(), anyString()))
                .thenReturn("dev:firstclub:rl:payconfirm:10:cust-1");
        lenient().when(keyFactory.rateLimitWebhookKey(anyString(), anyString()))
                .thenReturn("dev:firstclub:rl:webhook:gateway:127.0.0.1");
        lenient().when(keyFactory.rateLimitApiKeyKey(anyString(), anyString()))
                .thenReturn("dev:firstclub:rl:apikey:10:abcd1234");

        rateLimiter = new RedisSlidingWindowRateLimiter(
                templateProvider, keyFactory, availabilityService, properties, eventRepository);
    }

    // ── Redis disabled / unavailable ──────────────────────────────────────────

    @Nested
    @DisplayName("When Redis is unavailable")
    class RedisUnavailableTests {

        @BeforeEach
        void disableRedis() {
            when(availabilityService.isAvailable()).thenReturn(false);
        }

        @Test
        @DisplayName("checkLimit returns permissive decision without touching Redis")
        void checkLimit_redisDown_returnsPermissive() {
            RateLimitDecision decision = rateLimiter.checkLimit(
                    RateLimitPolicy.AUTH_BY_IP, "127.0.0.1");

            assertThat(decision.allowed()).isTrue();
            // permissive() returns limit - 1 as remaining
            assertThat(decision.remaining()).isEqualTo(
                    RateLimitPolicy.AUTH_BY_IP.getDefaultLimit() - 1);
            verify(templateProvider, never()).getIfAvailable();
        }

        @Test
        @DisplayName("isEnabled returns false when Redis is unavailable")
        void isEnabled_returnsTrue() {
            assertThat(rateLimiter.isEnabled()).isFalse();
        }
    }

    // ── Rate limit disabled via configuration ─────────────────────────────────

    @Nested
    @DisplayName("When rate limiting is disabled via config")
    class RateLimitDisabledTests {

        @BeforeEach
        void disableRateLimit() {
            when(properties.isEnabled()).thenReturn(false);
            lenient().when(availabilityService.isAvailable()).thenReturn(true);
        }

        @Test
        @DisplayName("checkLimit returns permissive decision immediately")
        void checkLimit_disabled_returnsPermissive() {
            RateLimitDecision decision = rateLimiter.checkLimit(
                    RateLimitPolicy.APIKEY_GENERAL, "merchant-1", "abcd1234");

            assertThat(decision.allowed()).isTrue();
            // Disabled path re-uses permissive() which returns defaultLimit - 1
            assertThat(decision.remaining()).isEqualTo(
                    RateLimitPolicy.APIKEY_GENERAL.getDefaultLimit() - 1);
        }

        @Test
        @DisplayName("isEnabled returns false")
        void isEnabled_returnsFalse() {
            assertThat(rateLimiter.isEnabled()).isFalse();
        }
    }

    // ── Lua script result handling ─────────────────────────────────────────────

    @Nested
    @DisplayName("When Redis is available")
    class RedisAvailableTests {

        @BeforeEach
        void enableRedis() {
            lenient().when(availabilityService.isAvailable()).thenReturn(true);
            lenient().when(templateProvider.getIfAvailable()).thenReturn(redisTemplate);
        }

        @Test
        @DisplayName("checkLimit allows the request when Lua script returns 1")
        void checkLimit_luaAllows_returnsPermit() {
            // Lua returns [allowed=1, remaining=19, resetMs=epochMs]
            long resetMs = System.currentTimeMillis() + Duration.ofMinutes(5).toMillis();
            when(redisTemplate.execute(
                    any(RedisScript.class), anyList(), any(Object[].class)))
                    .thenReturn(java.util.List.of(1L, 19L, resetMs));

            RateLimitDecision decision = rateLimiter.checkLimit(
                    RateLimitPolicy.AUTH_BY_IP, "127.0.0.1");

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.remaining()).isEqualTo(19);
            assertThat(decision.limit()).isEqualTo(RateLimitPolicy.AUTH_BY_IP.getDefaultLimit());
        }

        @Test
        @DisplayName("checkLimit denies the request when Lua script returns 0")
        void checkLimit_luaDenies_returnsDeny() {
            long resetMs = System.currentTimeMillis() + Duration.ofMinutes(5).toMillis();
            when(redisTemplate.execute(
                    any(RedisScript.class), anyList(), any(Object[].class)))
                    .thenReturn(java.util.List.of(0L, 0L, resetMs));

            RateLimitDecision decision = rateLimiter.checkLimit(
                    RateLimitPolicy.AUTH_BY_IP, "127.0.0.1");

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.remaining()).isZero();
        }

        @Test
        @DisplayName("checkLimit falls back to permissive when Lua throws")
        void checkLimit_luaException_returnsPermissive() {
            when(redisTemplate.execute(
                    any(RedisScript.class), anyList(), any(Object[].class)))
                    .thenThrow(new RuntimeException("Redis connection reset"));

            RateLimitDecision decision = rateLimiter.checkLimit(
                    RateLimitPolicy.AUTH_BY_IP, "127.0.0.1");

            assertThat(decision.allowed()).isTrue();
            // permissive() returns limit - 1 as remaining
            assertThat(decision.remaining()).isEqualTo(
                    RateLimitPolicy.AUTH_BY_IP.getDefaultLimit() - 1);
        }

        @Test
        @DisplayName("PAYMENT_CONFIRM policy uses two-subject key")
        void checkLimit_paymentConfirm_usesTwoSubjects() {
            long resetMs = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
            when(redisTemplate.execute(
                    any(RedisScript.class), anyList(), any(Object[].class)))
                    .thenReturn(java.util.List.of(1L, 9L, resetMs));

            RateLimitDecision decision = rateLimiter.checkLimit(
                    RateLimitPolicy.PAYMENT_CONFIRM, "merchant-10", "cust-1");

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.policy()).isEqualTo(RateLimitPolicy.PAYMENT_CONFIRM);
            verify(keyFactory).rateLimitPayConfirmKey("merchant-10", "cust-1");
        }

        @Test
        @DisplayName("Block event is persisted when Lua denies")
        void checkLimit_denied_persistsAuditRecord() {
            long resetMs = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
            when(redisTemplate.execute(
                    any(RedisScript.class), anyList(), any(Object[].class)))
                    .thenReturn(java.util.List.of(0L, 0L, resetMs));

            rateLimiter.checkLimit(RateLimitPolicy.WEBHOOK_INGEST, "gateway", "10.0.0.1");

            verify(eventRepository, timeout(200)).save(argThat(e ->
                    e.getCategory().equals("WEBHOOK_INGEST") && e.isBlocked()));
        }

        @Test
        @DisplayName("getBlocksLastHour delegates to repository")
        void getBlocksLastHour_delegatesToRepository() {
            when(eventRepository.countBlocksLastHour()).thenReturn(7L);
            assertThat(rateLimiter.getBlocksLastHour()).isEqualTo(7L);
        }
    }
}
