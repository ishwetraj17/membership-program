package com.firstclub.payments.routing.cache;

import com.firstclub.payments.routing.dto.GatewayHealthResponseDTO;
import com.firstclub.payments.routing.entity.GatewayHealthStatus;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayHealthCache Unit Tests")
class GatewayHealthCacheTest {

    @Mock private ObjectProvider<StringRedisTemplate> templateProvider;
    @Mock private RedisKeyFactory keyFactory;
    @Mock private RedisJsonCodec codec;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private GatewayHealthCache cache;

    @BeforeEach
    void setUp() {
        cache = new GatewayHealthCache(templateProvider, keyFactory, codec);
        lenient().when(templateProvider.getIfAvailable()).thenReturn(redisTemplate);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(keyFactory.gatewayHealthKey(anyString())).thenAnswer(
                inv -> "dev:firstclub:gw:health:" + inv.getArgument(0).toString().toUpperCase());
    }

    private GatewayHealthResponseDTO dto(String name, GatewayHealthStatus status) {
        GatewayHealthResponseDTO d = new GatewayHealthResponseDTO();
        d.setGatewayName(name);
        d.setStatus(status);
        return d;
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("returns deserialised DTO on cache HIT")
        void cacheHit_returnsDTO() {
            GatewayHealthResponseDTO expected = dto("razorpay", GatewayHealthStatus.HEALTHY);
            when(valueOps.get("dev:firstclub:gw:health:RAZORPAY")).thenReturn("{\"json\":true}");
            when(codec.tryFromJson("{\"json\":true}", GatewayHealthResponseDTO.class))
                    .thenReturn(Optional.of(expected));

            Optional<GatewayHealthResponseDTO> result = cache.get("razorpay");

            assertThat(result).isPresent();
            assertThat(result.get().getStatus()).isEqualTo(GatewayHealthStatus.HEALTHY);
        }

        @Test
        @DisplayName("returns empty on cache MISS (null Redis value)")
        void cacheMiss_returnsEmpty() {
            when(valueOps.get(anyString())).thenReturn(null);

            Optional<GatewayHealthResponseDTO> result = cache.get("razorpay");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty when Redis template is unavailable")
        void redisUnavailable_returnsEmpty() {
            when(templateProvider.getIfAvailable()).thenReturn(null);

            Optional<GatewayHealthResponseDTO> result = cache.get("razorpay");

            assertThat(result).isEmpty();
            verifyNoInteractions(valueOps);
        }

        @Test
        @DisplayName("returns empty and swallows exception on Redis error")
        void redisError_returnsEmpty() {
            when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis error"));

            Optional<GatewayHealthResponseDTO> result = cache.get("razorpay");

            assertThat(result).isEmpty();
        }
    }

    // ── put ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("put")
    class Put {

        @Test
        @DisplayName("serialises and stores with configured TTL")
        void storesWithTtl() {
            GatewayHealthResponseDTO dto = dto("razorpay", GatewayHealthStatus.HEALTHY);
            when(codec.toJson(dto)).thenReturn("{\"gatewayName\":\"razorpay\"}");

            cache.put("razorpay", dto);

            verify(valueOps).set(
                    eq("dev:firstclub:gw:health:RAZORPAY"),
                    eq("{\"gatewayName\":\"razorpay\"}"),
                    eq(Duration.ofSeconds(GatewayHealthCache.TTL_SECONDS)));
        }

        @Test
        @DisplayName("silently ignores write when Redis is unavailable")
        void redisUnavailable_noOp() {
            when(templateProvider.getIfAvailable()).thenReturn(null);

            cache.put("razorpay", dto("razorpay", GatewayHealthStatus.HEALTHY));

            verifyNoInteractions(valueOps);
        }

        @Test
        @DisplayName("swallows exception on write error")
        void writeError_swallowed() {
            when(codec.toJson(any())).thenThrow(new RuntimeException("serialise error"));

            // should not throw
            cache.put("razorpay", dto("razorpay", GatewayHealthStatus.HEALTHY));
        }
    }

    // ── evict ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("evict")
    class Evict {

        @Test
        @DisplayName("deletes the Redis key for the given gateway")
        void deletesKey() {
            cache.evict("razorpay");

            verify(redisTemplate).delete("dev:firstclub:gw:health:RAZORPAY");
        }

        @Test
        @DisplayName("silently ignores evict when Redis is unavailable")
        void redisUnavailable_noOp() {
            when(templateProvider.getIfAvailable()).thenReturn(null);

            cache.evict("razorpay");

            verifyNoInteractions(redisTemplate);
        }
    }
}
