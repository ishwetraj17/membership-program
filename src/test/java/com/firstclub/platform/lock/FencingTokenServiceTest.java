package com.firstclub.platform.lock;

import com.firstclub.platform.errors.StaleOperationException;
import com.firstclub.platform.lock.fencing.FencingTokenService;
import com.firstclub.platform.redis.RedisKeyFactory;
import com.firstclub.platform.redis.RedisOpsFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FencingTokenService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FencingTokenService — Unit Tests")
class FencingTokenServiceTest {

    @Mock private ObjectProvider<RedisOpsFacade> redisProvider;
    @Mock private RedisOpsFacade ops;
    @Mock private RedisKeyFactory keyFactory;

    private FencingTokenService service;

    private static final String RESOURCE_TYPE = "subscription";
    private static final String RESOURCE_ID = "99";
    private static final String FENCE_KEY = "prod:firstclub:fence:subscription:99";

    @BeforeEach
    void setUp() {
        service = new FencingTokenService(redisProvider, keyFactory);
    }

    @Nested
    @DisplayName("TokenGeneration — Redis INCR monotonicity")
    class TokenGeneration {

        @Test
        @DisplayName("generateToken_incrementsMonotonically — each acquire gets a higher token")
        void generateToken_incrementsMonotonically() {
            when(redisProvider.getIfAvailable()).thenReturn(ops);
            when(keyFactory.fenceTokenKey(RESOURCE_TYPE, RESOURCE_ID)).thenReturn(FENCE_KEY);
            when(ops.increment(FENCE_KEY)).thenReturn(1L, 2L, 3L);

            long t1 = service.generateToken(RESOURCE_TYPE, RESOURCE_ID);
            long t2 = service.generateToken(RESOURCE_TYPE, RESOURCE_ID);
            long t3 = service.generateToken(RESOURCE_TYPE, RESOURCE_ID);

            assertThat(t1).isEqualTo(1L);
            assertThat(t2).isEqualTo(2L);
            assertThat(t3).isEqualTo(3L);
            assertThat(t1).isLessThan(t2);
            assertThat(t2).isLessThan(t3);
        }

        @Test
        @DisplayName("generateToken_redisUnavailable_throwsIllegalStateException")
        void generateToken_redisUnavailable_throwsIllegalStateException() {
            when(redisProvider.getIfAvailable()).thenReturn(null);

            assertThatThrownBy(() -> service.generateToken(RESOURCE_TYPE, RESOURCE_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Redis is required for fence token generation");
        }

        @Test
        @DisplayName("generateToken_callsIncrementOnCorrectKey")
        void generateToken_callsIncrementOnCorrectKey() {
            when(redisProvider.getIfAvailable()).thenReturn(ops);
            when(keyFactory.fenceTokenKey(RESOURCE_TYPE, RESOURCE_ID)).thenReturn(FENCE_KEY);
            when(ops.increment(FENCE_KEY)).thenReturn(5L);

            long token = service.generateToken(RESOURCE_TYPE, RESOURCE_ID);

            assertThat(token).isEqualTo(5L);
        }
    }

    @Nested
    @DisplayName("TokenValidation — isTokenValid logic")
    class TokenValidation {

        @Test
        @DisplayName("isTokenValid_higher_accepted — incoming > stored is valid (newer lock holder)")
        void isTokenValid_higher_accepted() {
            assertThat(service.isTokenValid(10L, 5L)).isTrue();
        }

        @Test
        @DisplayName("isTokenValid_stale_rejected — incoming < stored is invalid (older lock holder)")
        void isTokenValid_stale_rejected() {
            assertThat(service.isTokenValid(3L, 8L)).isFalse();
        }

        @Test
        @DisplayName("isTokenValid_equal_accepted — same token is valid (idempotent retry of same holder)")
        void isTokenValid_equal_accepted() {
            assertThat(service.isTokenValid(5L, 5L)).isTrue();
        }

        @Test
        @DisplayName("isTokenValid_zero_compared_to_nonZero — lower bound rejected")
        void isTokenValid_zeroIsStaleVsNonZero() {
            assertThat(service.isTokenValid(0L, 1L)).isFalse();
        }
    }

    @Nested
    @DisplayName("TokenEnforcement — enforceTokenValidity contract")
    class TokenEnforcement {

        @Test
        @DisplayName("enforceTokenValidity_stale_throws — StaleOperationException for incoming < stored")
        void enforceTokenValidity_stale_throws() {
            assertThatThrownBy(() ->
                    service.enforceTokenValidity("Subscription", 99L, 2L, 7L))
                    .isInstanceOf(StaleOperationException.class);
        }

        @Test
        @DisplayName("enforceTokenValidity_valid_passes — no exception for incoming >= stored")
        void enforceTokenValidity_valid_passes() {
            assertThatNoException().isThrownBy(() ->
                    service.enforceTokenValidity("Subscription", 99L, 8L, 7L));
        }

        @Test
        @DisplayName("enforceTokenValidity_equalTokens_passes — idempotent retry scenario")
        void enforceTokenValidity_equalTokens_passes() {
            assertThatNoException().isThrownBy(() ->
                    service.enforceTokenValidity("Invoice", "inv-001", 15L, 15L));
        }

        @Test
        @DisplayName("enforceTokenValidity_stale_includesEntityInfoInException")
        void enforceTokenValidity_stale_includesEntityInfoInException() {
            assertThatThrownBy(() ->
                    service.enforceTokenValidity("PaymentIntent", "pi-42", 1L, 10L))
                    .isInstanceOf(StaleOperationException.class)
                    .hasMessageContaining("PaymentIntent");
        }
    }
}
