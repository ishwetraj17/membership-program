package com.firstclub.payments.routing.cache;

import com.firstclub.payments.routing.dto.GatewayRouteRuleResponseDTO;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoutingRuleCache Unit Tests")
class RoutingRuleCacheTest {

    @Mock private ObjectProvider<StringRedisTemplate> templateProvider;
    @Mock private RedisKeyFactory keyFactory;
    @Mock private RedisJsonCodec codec;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private RoutingRuleCache cache;

    @BeforeEach
    void setUp() {
        cache = new RoutingRuleCache(templateProvider, keyFactory, codec);
        lenient().when(templateProvider.getIfAvailable()).thenReturn(redisTemplate);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(keyFactory.routingRuleCacheKey(anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> "dev:firstclub:routing:" + inv.getArgument(0)
                        + ":" + inv.getArgument(1) + ":" + inv.getArgument(2)
                        + ":" + inv.getArgument(3));
    }

    private GatewayRouteRuleResponseDTO rule(Long id, String preferred) {
        GatewayRouteRuleResponseDTO r = new GatewayRouteRuleResponseDTO();
        r.setId(id);
        r.setPreferredGateway(preferred);
        r.setPaymentMethodType("CARD");
        r.setCurrency("INR");
        r.setRetryNumber(1);
        r.setActive(true);
        return r;
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("returns deserialised rule list on cache HIT")
        void cacheHit_returnsList() {
            List<GatewayRouteRuleResponseDTO> expected = List.of(rule(1L, "razorpay"));
            when(valueOps.get("dev:firstclub:routing:42:CARD:INR:1")).thenReturn("[{\"id\":1}]");
            when(codec.fromJson(eq("[{\"id\":1}]"), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                    .thenReturn(expected);

            Optional<List<GatewayRouteRuleResponseDTO>> result = cache.get("42", "CARD", "INR", 1);

            assertThat(result).isPresent();
            assertThat(result.get()).hasSize(1);
            assertThat(result.get().get(0).getPreferredGateway()).isEqualTo("razorpay");
        }

        @Test
        @DisplayName("returns empty on cache MISS (null Redis value)")
        void cacheMiss_returnsEmpty() {
            when(valueOps.get(anyString())).thenReturn(null);

            Optional<List<GatewayRouteRuleResponseDTO>> result = cache.get("42", "CARD", "INR", 1);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty when Redis template is unavailable")
        void redisUnavailable_returnsEmpty() {
            when(templateProvider.getIfAvailable()).thenReturn(null);

            Optional<List<GatewayRouteRuleResponseDTO>> result = cache.get("global", "UPI", "INR", 1);

            assertThat(result).isEmpty();
            verifyNoInteractions(valueOps);
        }

        @Test
        @DisplayName("returns empty and swallows exception on Redis error")
        void redisError_returnsEmpty() {
            when(valueOps.get(anyString())).thenThrow(new RuntimeException("timeout"));

            Optional<List<GatewayRouteRuleResponseDTO>> result = cache.get("42", "CARD", "INR", 1);

            assertThat(result).isEmpty();
        }
    }

    // ── put ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("put")
    class Put {

        @Test
        @DisplayName("serialises rule list and stores with configured TTL")
        void storesWithTtl() {
            List<GatewayRouteRuleResponseDTO> rules = List.of(rule(1L, "razorpay"));
            when(codec.toJson(rules)).thenReturn("[{\"id\":1}]");

            cache.put("42", "CARD", "INR", 1, rules);

            verify(valueOps).set(
                    eq("dev:firstclub:routing:42:CARD:INR:1"),
                    eq("[{\"id\":1}]"),
                    eq(Duration.ofSeconds(RoutingRuleCache.TTL_SECONDS)));
        }

        @Test
        @DisplayName("stores empty list (no active rules) to prevent repeated DB calls")
        void storesEmptyList() {
            when(codec.toJson(anyList())).thenReturn("[]");

            cache.put("global", "CARD", "USD", 2, List.of());

            verify(valueOps).set(anyString(), eq("[]"),
                    eq(Duration.ofSeconds(RoutingRuleCache.TTL_SECONDS)));
        }

        @Test
        @DisplayName("silently ignores write when Redis is unavailable")
        void redisUnavailable_noOp() {
            when(templateProvider.getIfAvailable()).thenReturn(null);

            cache.put("42", "CARD", "INR", 1, List.of(rule(1L, "razorpay")));

            verifyNoInteractions(valueOps);
        }
    }

    // ── evict ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("evict")
    class Evict {

        @Test
        @DisplayName("deletes the exact cache key for the given discriminators")
        void deletesKey() {
            cache.evict("42", "CARD", "INR", 1);

            verify(redisTemplate).delete("dev:firstclub:routing:42:CARD:INR:1");
        }

        @Test
        @DisplayName("evicts global scope key (platform default rules)")
        void evictsGlobalScope() {
            cache.evict("global", "UPI", "INR", 1);

            verify(redisTemplate).delete("dev:firstclub:routing:global:UPI:INR:1");
        }

        @Test
        @DisplayName("silently ignores evict when Redis is unavailable")
        void redisUnavailable_noOp() {
            when(templateProvider.getIfAvailable()).thenReturn(null);

            cache.evict("42", "CARD", "INR", 1);

            verifyNoInteractions(redisTemplate);
        }
    }
}
