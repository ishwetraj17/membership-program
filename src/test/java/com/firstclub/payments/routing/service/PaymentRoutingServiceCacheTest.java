package com.firstclub.payments.routing.service;

import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.entity.PaymentMethod;
import com.firstclub.payments.entity.PaymentMethodType;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.routing.cache.GatewayHealthCache;
import com.firstclub.payments.routing.cache.RoutingRuleCache;
import com.firstclub.payments.routing.dto.GatewayHealthResponseDTO;
import com.firstclub.payments.routing.dto.GatewayRouteRuleCreateRequestDTO;
import com.firstclub.payments.routing.dto.GatewayRouteRuleResponseDTO;
import com.firstclub.payments.routing.dto.GatewayRouteRuleUpdateRequestDTO;
import com.firstclub.payments.routing.dto.RoutingDecisionDTO;
import com.firstclub.payments.routing.dto.RoutingDecisionSnapshot;
import com.firstclub.payments.routing.entity.GatewayHealth;
import com.firstclub.payments.routing.entity.GatewayHealthStatus;
import com.firstclub.payments.routing.entity.GatewayRouteRule;
import com.firstclub.payments.routing.exception.RoutingException;
import com.firstclub.payments.routing.repository.GatewayHealthRepository;
import com.firstclub.payments.routing.repository.GatewayRouteRuleRepository;
import com.firstclub.payments.routing.service.impl.PaymentRoutingServiceImpl;
import com.firstclub.platform.redis.RedisJsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentRoutingService — Cache Behaviour Tests")
class PaymentRoutingServiceCacheTest {

    @Mock private GatewayRouteRuleRepository routeRuleRepository;
    @Mock private GatewayHealthRepository    healthRepository;
    @Mock private PaymentAttemptRepository   paymentAttemptRepository;
    @Mock private GatewayHealthCache         gatewayHealthCache;
    @Mock private RoutingRuleCache           routingRuleCache;
    @Mock private RedisJsonCodec             codec;

    @InjectMocks
    private PaymentRoutingServiceImpl service;

    private PaymentIntentV2 intent;
    private PaymentMethod pm;

    @BeforeEach
    void setUp() {
        MerchantAccount merchant = new MerchantAccount();
        merchant.setId(42L);

        intent = new PaymentIntentV2();
        intent.setMerchant(merchant);
        intent.setCurrency("INR");

        pm = new PaymentMethod();
        pm.setMethodType(PaymentMethodType.CARD);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private GatewayRouteRule ruleEntity(long id, String preferred, String fallback) {
        GatewayRouteRule r = new GatewayRouteRule();
        r.setId(id);
        r.setPriority(10);
        r.setMerchantId(42L);
        r.setPreferredGateway(preferred);
        r.setFallbackGateway(fallback);
        r.setRetryNumber(1);
        r.setActive(true);
        r.setPaymentMethodType("CARD");
        r.setCurrency("INR");
        return r;
    }

    private GatewayRouteRuleResponseDTO ruleDto(long id, String preferred, String fallback) {
        GatewayRouteRuleResponseDTO dto = new GatewayRouteRuleResponseDTO();
        dto.setId(id);
        dto.setMerchantId(42L);
        dto.setPreferredGateway(preferred);
        dto.setFallbackGateway(fallback);
        dto.setRetryNumber(1);
        dto.setActive(true);
        dto.setPaymentMethodType("CARD");
        dto.setCurrency("INR");
        return dto;
    }

    private GatewayHealthResponseDTO healthDto(GatewayHealthStatus status) {
        GatewayHealthResponseDTO h = new GatewayHealthResponseDTO();
        h.setStatus(status);
        return h;
    }

    // ── Routing rule cache ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Routing rule cache")
    class RoutingRuleCacheBehaviour {

        @Test
        @DisplayName("HIT: no DB call when cache returns rules")
        void cacheHit_noDbQuery() {
            GatewayRouteRuleResponseDTO dto = ruleDto(1L, "razorpay", null);
            when(routingRuleCache.get("42", "CARD", "INR", 1))
                    .thenReturn(Optional.of(List.of(dto)));
            when(gatewayHealthCache.get("razorpay"))
                    .thenReturn(Optional.of(healthDto(GatewayHealthStatus.HEALTHY)));

            RoutingDecisionDTO result = service.selectGatewayForAttempt(intent, pm, 1);

            assertThat(result.getSelectedGateway()).isEqualTo("razorpay");
            // DB repository must NOT be consulted when cache hit
            verify(routeRuleRepository, never())
                    .findActiveRulesForMerchantAndMethodAndCurrency(anyLong(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("MISS: falls back to DB and populates cache")
        void cacheMiss_queriesDbAndPopulatesCache() {
            GatewayRouteRule entity = ruleEntity(1L, "razorpay", null);
            when(routingRuleCache.get("42", "CARD", "INR", 1)).thenReturn(Optional.empty());
            when(routeRuleRepository.findActiveRulesForMerchantAndMethodAndCurrency(42L, "CARD", "INR", 1))
                    .thenReturn(List.of(entity));
            when(healthRepository.findByGatewayName("razorpay"))
                    .thenReturn(Optional.of(new GatewayHealth() {{ setGatewayName("razorpay"); setStatus(GatewayHealthStatus.HEALTHY); }}));

            service.selectGatewayForAttempt(intent, pm, 1);

            verify(routeRuleRepository).findActiveRulesForMerchantAndMethodAndCurrency(42L, "CARD", "INR", 1);
            // After DB query, cache must be populated for next request
            verify(routingRuleCache).put(eq("42"), eq("CARD"), eq("INR"), eq(1), anyList());
        }

        @Test
        @DisplayName("MISS on merchant rules falls back to global scope")
        void cacheMiss_merchantThenGlobal() {
            when(routingRuleCache.get("42", "CARD", "INR", 1)).thenReturn(Optional.empty());
            when(routingRuleCache.get("global", "CARD", "INR", 1)).thenReturn(Optional.empty());
            when(routeRuleRepository.findActiveRulesForMerchantAndMethodAndCurrency(42L, "CARD", "INR", 1))
                    .thenReturn(List.of());
            when(routeRuleRepository.findPlatformDefaultRules("CARD", "INR", 1))
                    .thenReturn(List.of(ruleEntity(9L, "stripe", null)));
            when(healthRepository.findByGatewayName("stripe"))
                    .thenReturn(Optional.empty()); // unknown → defaults to HEALTHY

            RoutingDecisionDTO result = service.selectGatewayForAttempt(intent, pm, 1);

            assertThat(result.getSelectedGateway()).isEqualTo("stripe");
            verify(routingRuleCache).put(eq("global"), eq("CARD"), eq("INR"), eq(1), anyList());
        }
    }

    // ── Gateway health cache ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Gateway health cache")
    class GatewayHealthCacheBehaviour {

        @Test
        @DisplayName("HIT: no DB call when health cache has entry")
        void healthCacheHit_noDbQuery() {
            when(routingRuleCache.get("42", "CARD", "INR", 1))
                    .thenReturn(Optional.of(List.of(ruleDto(1L, "razorpay", null))));
            when(gatewayHealthCache.get("razorpay"))
                    .thenReturn(Optional.of(healthDto(GatewayHealthStatus.HEALTHY)));

            service.selectGatewayForAttempt(intent, pm, 1);

            verify(healthRepository, never()).findByGatewayName("razorpay");
        }

        @Test
        @DisplayName("MISS: queries DB when health not in cache")
        void healthCacheMiss_queriesDb() {
            when(routingRuleCache.get("42", "CARD", "INR", 1))
                    .thenReturn(Optional.of(List.of(ruleDto(1L, "razorpay", null))));
            when(gatewayHealthCache.get("razorpay")).thenReturn(Optional.empty());
            when(healthRepository.findByGatewayName("razorpay"))
                    .thenReturn(Optional.of(new GatewayHealth() {{ setGatewayName("razorpay"); setStatus(GatewayHealthStatus.HEALTHY); }}));

            service.selectGatewayForAttempt(intent, pm, 1);

            verify(healthRepository).findByGatewayName("razorpay");
        }
    }

    // ── Cache eviction on mutations ───────────────────────────────────────────

    @Nested
    @DisplayName("Cache eviction on mutations")
    class CacheEviction {

        @Test
        @DisplayName("createRouteRule evicts the routing rule cache for the rule's scope")
        void createRouteRule_evictsCache() {
            GatewayRouteRuleCreateRequestDTO req = new GatewayRouteRuleCreateRequestDTO();
            req.setMerchantId(42L);
            req.setPreferredGateway("razorpay");
            req.setPaymentMethodType("CARD");
            req.setCurrency("INR");
            req.setRetryNumber(1);
            req.setPriority(10);
            GatewayRouteRule saved = ruleEntity(1L, "razorpay", null);
            when(routeRuleRepository.save(any())).thenReturn(saved);

            service.createRouteRule(req);

            verify(routingRuleCache).evict("42", "CARD", "INR", 1);
        }

        @Test
        @DisplayName("updateRouteRule evicts the routing rule cache after saving")
        void updateRouteRule_evictsCache() {
            GatewayRouteRule existing = ruleEntity(5L, "razorpay", null);
            GatewayRouteRuleUpdateRequestDTO req = new GatewayRouteRuleUpdateRequestDTO();
            req.setPreferredGateway("stripe");
            when(routeRuleRepository.findById(5L)).thenReturn(Optional.of(existing));
            when(routeRuleRepository.save(any())).thenReturn(existing);

            service.updateRouteRule(5L, req);

            verify(routingRuleCache).evict("42", "CARD", "INR", 1);
        }

        @Test
        @DisplayName("deactivateRouteRule sets active=false and evicts cache")
        void deactivateRouteRule_deactivatesAndEvicts() {
            GatewayRouteRule existing = ruleEntity(7L, "razorpay", null);
            when(routeRuleRepository.findById(7L)).thenReturn(Optional.of(existing));
            when(routeRuleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.deactivateRouteRule(7L);

            assertThat(existing.isActive()).isFalse();
            verify(routingRuleCache).evict("42", "CARD", "INR", 1);
        }

        @Test
        @DisplayName("deactivateRouteRule throws ROUTE_RULE_NOT_FOUND for unknown id")
        void deactivateRouteRule_unknownId_throws() {
            when(routeRuleRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deactivateRouteRule(999L))
                    .isInstanceOf(RoutingException.class);

            verify(routingRuleCache, never()).evict(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("global-scope rule (null merchantId) evicts 'global' cache key")
        void globalRule_evictsGlobalScope() {
            GatewayRouteRule globalRule = ruleEntity(3L, "razorpay", null);
            globalRule.setMerchantId(null); // platform default = global scope
            when(routeRuleRepository.findById(3L)).thenReturn(Optional.of(globalRule));
            when(routeRuleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.deactivateRouteRule(3L);

            verify(routingRuleCache).evict("global", "CARD", "INR", 1);
        }
    }

    // ── getRoutingDecision ────────────────────────────────────────────────────

    @Nested
    @DisplayName("getRoutingDecision")
    class GetRoutingDecision {

        @Test
        @DisplayName("returns deserialized snapshot when routingSnapshotJson is populated")
        void returnsSnapshot_whenJsonPresent() {
            PaymentAttempt attempt = new PaymentAttempt();
            attempt.setRoutingSnapshotJson("{\"selectedGateway\":\"razorpay\"}");
            RoutingDecisionSnapshot snap = new RoutingDecisionSnapshot();
            snap.setSelectedGateway("razorpay");
            when(paymentAttemptRepository.findById(10L)).thenReturn(Optional.of(attempt));
            when(codec.tryFromJson("{\"selectedGateway\":\"razorpay\"}", RoutingDecisionSnapshot.class))
                    .thenReturn(Optional.of(snap));

            Optional<RoutingDecisionSnapshot> result = service.getRoutingDecision(10L);

            assertThat(result).isPresent();
            assertThat(result.get().getSelectedGateway()).isEqualTo("razorpay");
        }

        @Test
        @DisplayName("returns empty when routingSnapshotJson is null (legacy attempt)")
        void returnsEmpty_whenJsonNull() {
            PaymentAttempt attempt = new PaymentAttempt();
            attempt.setRoutingSnapshotJson(null);
            when(paymentAttemptRepository.findById(11L)).thenReturn(Optional.of(attempt));

            Optional<RoutingDecisionSnapshot> result = service.getRoutingDecision(11L);

            assertThat(result).isEmpty();
            verifyNoInteractions(codec);
        }

        @Test
        @DisplayName("returns empty when routingSnapshotJson is blank")
        void returnsEmpty_whenJsonBlank() {
            PaymentAttempt attempt = new PaymentAttempt();
            attempt.setRoutingSnapshotJson("   ");
            when(paymentAttemptRepository.findById(12L)).thenReturn(Optional.of(attempt));

            Optional<RoutingDecisionSnapshot> result = service.getRoutingDecision(12L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("throws exception when attempt not found")
        void throws_whenAttemptNotFound() {
            when(paymentAttemptRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getRoutingDecision(99L))
                    .isInstanceOf(RoutingException.class);
        }
    }

    // ── Snapshot in RoutingDecisionDTO ────────────────────────────────────────

    @Nested
    @DisplayName("Snapshot captured in RoutingDecisionDTO")
    class SnapshotInDto {

        @Test
        @DisplayName("snapshotJson is included in the returned RoutingDecisionDTO when codec succeeds")
        void snapshotJson_includedInResult() {
            GatewayRouteRuleResponseDTO dto = ruleDto(1L, "razorpay", null);
            when(routingRuleCache.get("42", "CARD", "INR", 1))
                    .thenReturn(Optional.of(List.of(dto)));
            when(gatewayHealthCache.get("razorpay"))
                    .thenReturn(Optional.of(healthDto(GatewayHealthStatus.HEALTHY)));
            when(codec.toJson(any(RoutingDecisionSnapshot.class)))
                    .thenReturn("{\"selectedGateway\":\"razorpay\"}");

            RoutingDecisionDTO result = service.selectGatewayForAttempt(intent, pm, 1);

            assertThat(result.getSnapshotJson()).isEqualTo("{\"selectedGateway\":\"razorpay\"}");
        }

        @Test
        @DisplayName("snapshotJson is null when codec throws — routing still succeeds")
        void snapshotJson_nullWhenCodecFails_routingSucceeds() {
            GatewayRouteRuleResponseDTO dto = ruleDto(1L, "razorpay", null);
            when(routingRuleCache.get("42", "CARD", "INR", 1))
                    .thenReturn(Optional.of(List.of(dto)));
            when(gatewayHealthCache.get("razorpay"))
                    .thenReturn(Optional.of(healthDto(GatewayHealthStatus.HEALTHY)));
            when(codec.toJson(any(RoutingDecisionSnapshot.class)))
                    .thenThrow(new RuntimeException("serialization failed"));

            RoutingDecisionDTO result = service.selectGatewayForAttempt(intent, pm, 1);

            // Routing decision succeeds even if snapshot cannot be serialised
            assertThat(result.getSelectedGateway()).isEqualTo("razorpay");
            assertThat(result.getSnapshotJson()).isNull();
        }
    }
}
