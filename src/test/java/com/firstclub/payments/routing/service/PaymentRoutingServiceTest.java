package com.firstclub.payments.routing.service;

import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.entity.PaymentMethod;
import com.firstclub.payments.entity.PaymentMethodType;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.routing.cache.GatewayHealthCache;
import com.firstclub.payments.routing.cache.RoutingRuleCache;
import com.firstclub.payments.routing.dto.GatewayRouteRuleCreateRequestDTO;
import com.firstclub.payments.routing.dto.GatewayRouteRuleResponseDTO;
import com.firstclub.payments.routing.dto.GatewayRouteRuleUpdateRequestDTO;
import com.firstclub.payments.routing.dto.RoutingDecisionDTO;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentRoutingService Unit Tests")
class PaymentRoutingServiceTest {

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
        merchant.setId(1L);

        intent = new PaymentIntentV2();
        intent.setMerchant(merchant);
        intent.setCurrency("INR");

        pm = new PaymentMethod();
        pm.setMethodType(PaymentMethodType.CARD);
    }

    // ── Helper factories ──────────────────────────────────────────────────────

    private GatewayRouteRule rule(long id, String preferred, String fallback) {
        GatewayRouteRule r = new GatewayRouteRule();
        r.setId(id);
        r.setPriority(10);
        r.setPreferredGateway(preferred);
        r.setFallbackGateway(fallback);
        r.setRetryNumber(1);
        r.setActive(true);
        r.setPaymentMethodType("CARD");
        r.setCurrency("INR");
        return r;
    }

    private GatewayHealth health(String name, GatewayHealthStatus status) {
        GatewayHealth h = new GatewayHealth();
        h.setGatewayName(name);
        h.setStatus(status);
        return h;
    }

    // ── selectGatewayForAttempt ───────────────────────────────────────────────

    @Nested
    @DisplayName("selectGatewayForAttempt")
    class SelectGateway {

        @Test
        @DisplayName("returns preferred gateway when it is HEALTHY")
        void preferredHealthy_returnsPreferred() {
            GatewayRouteRule r = rule(1L, "razorpay", "stripe");
            when(routeRuleRepository.findActiveRulesForMerchantAndMethodAndCurrency(
                    1L, "CARD", "INR", 1)).thenReturn(List.of(r));
            when(healthRepository.findByGatewayName("razorpay"))
                    .thenReturn(Optional.of(health("razorpay", GatewayHealthStatus.HEALTHY)));

            RoutingDecisionDTO result = service.selectGatewayForAttempt(intent, pm, 1);

            assertThat(result.getSelectedGateway()).isEqualTo("razorpay");
            assertThat(result.isFallback()).isFalse();
            assertThat(result.getRuleId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("returns preferred gateway when it is DEGRADED")
        void preferredDegraded_stillSelected() {
            GatewayRouteRule r = rule(2L, "razorpay", "stripe");
            when(routeRuleRepository.findActiveRulesForMerchantAndMethodAndCurrency(
                    1L, "CARD", "INR", 1)).thenReturn(List.of(r));
            when(healthRepository.findByGatewayName("razorpay"))
                    .thenReturn(Optional.of(health("razorpay", GatewayHealthStatus.DEGRADED)));

            RoutingDecisionDTO result = service.selectGatewayForAttempt(intent, pm, 1);

            assertThat(result.getSelectedGateway()).isEqualTo("razorpay");
            assertThat(result.isFallback()).isFalse();
        }

        @Test
        @DisplayName("falls back to fallback gateway when preferred is DOWN")
        void preferredDown_usesFallback() {
            GatewayRouteRule r = rule(3L, "razorpay", "stripe");
            when(routeRuleRepository.findActiveRulesForMerchantAndMethodAndCurrency(
                    1L, "CARD", "INR", 1)).thenReturn(List.of(r));
            when(healthRepository.findByGatewayName("razorpay"))
                    .thenReturn(Optional.of(health("razorpay", GatewayHealthStatus.DOWN)));
            when(healthRepository.findByGatewayName("stripe"))
                    .thenReturn(Optional.of(health("stripe", GatewayHealthStatus.HEALTHY)));

            RoutingDecisionDTO result = service.selectGatewayForAttempt(intent, pm, 1);

            assertThat(result.getSelectedGateway()).isEqualTo("stripe");
            assertThat(result.isFallback()).isTrue();
        }

        @Test
        @DisplayName("throws NO_ELIGIBLE_GATEWAY when both preferred and fallback are DOWN")
        void bothDown_throwsRoutingException() {
            GatewayRouteRule r = rule(4L, "razorpay", "stripe");
            when(routeRuleRepository.findActiveRulesForMerchantAndMethodAndCurrency(
                    1L, "CARD", "INR", 1)).thenReturn(List.of(r));
            when(healthRepository.findByGatewayName("razorpay"))
                    .thenReturn(Optional.of(health("razorpay", GatewayHealthStatus.DOWN)));
            when(healthRepository.findByGatewayName("stripe"))
                    .thenReturn(Optional.of(health("stripe", GatewayHealthStatus.DOWN)));

            assertThatThrownBy(() -> service.selectGatewayForAttempt(intent, pm, 1))
                    .isInstanceOf(RoutingException.class)
                    .hasMessageContaining("CARD")
                    .hasMessageContaining("INR");
        }

        @Test
        @DisplayName("throws NO_ELIGIBLE_GATEWAY when no rules match")
        void noRules_throwsRoutingException() {
            when(routeRuleRepository.findActiveRulesForMerchantAndMethodAndCurrency(
                    anyLong(), anyString(), anyString(), anyInt())).thenReturn(List.of());
            when(routeRuleRepository.findPlatformDefaultRules(
                    anyString(), anyString(), anyInt())).thenReturn(List.of());

            assertThatThrownBy(() -> service.selectGatewayForAttempt(intent, pm, 1))
                    .isInstanceOf(RoutingException.class)
                    .extracting(t -> ((RoutingException) t).getErrorCode())
                    .isEqualTo("NO_ELIGIBLE_GATEWAY");
        }

        @Test
        @DisplayName("merchant-specific rules take precedence over platform defaults")
        void merchantRulesBeatPlatformDefaults() {
            GatewayRouteRule merchantRule = rule(10L, "payu", null);
            GatewayRouteRule platformRule = rule(20L, "stripe", null);

            when(routeRuleRepository.findActiveRulesForMerchantAndMethodAndCurrency(
                    1L, "CARD", "INR", 1)).thenReturn(List.of(merchantRule));
            // Platform query should NOT be called when merchant rules found
            when(healthRepository.findByGatewayName("payu"))
                    .thenReturn(Optional.of(health("payu", GatewayHealthStatus.HEALTHY)));

            RoutingDecisionDTO result = service.selectGatewayForAttempt(intent, pm, 1);

            assertThat(result.getSelectedGateway()).isEqualTo("payu");
            verify(routeRuleRepository, never()).findPlatformDefaultRules(any(), any(), anyInt());
        }

        @Test
        @DisplayName("falls through to platform defaults when no merchant rules exist")
        void noMerchantRules_usesPlatformDefault() {
            GatewayRouteRule platformRule = rule(20L, "stripe", null);
            when(routeRuleRepository.findActiveRulesForMerchantAndMethodAndCurrency(
                    anyLong(), anyString(), anyString(), anyInt())).thenReturn(List.of());
            when(routeRuleRepository.findPlatformDefaultRules("CARD", "INR", 1))
                    .thenReturn(List.of(platformRule));
            when(healthRepository.findByGatewayName("stripe"))
                    .thenReturn(Optional.of(health("stripe", GatewayHealthStatus.HEALTHY)));

            RoutingDecisionDTO result = service.selectGatewayForAttempt(intent, pm, 1);

            assertThat(result.getSelectedGateway()).isEqualTo("stripe");
        }

        @Test
        @DisplayName("unregistered gateway defaults to HEALTHY (safe for sandbox)")
        void unknownGateway_treatedAsHealthy() {
            GatewayRouteRule r = rule(5L, "unknown-gw", null);
            when(routeRuleRepository.findActiveRulesForMerchantAndMethodAndCurrency(
                    1L, "CARD", "INR", 1)).thenReturn(List.of(r));
            when(healthRepository.findByGatewayName("unknown-gw")).thenReturn(Optional.empty());

            RoutingDecisionDTO result = service.selectGatewayForAttempt(intent, pm, 1);

            assertThat(result.getSelectedGateway()).isEqualTo("unknown-gw");
        }
    }

    // ── createRouteRule ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("createRouteRule")
    class CreateRouteRule {

        @Test
        @DisplayName("saves and returns the new rule")
        void savesNewRule() {
            GatewayRouteRuleCreateRequestDTO req = new GatewayRouteRuleCreateRequestDTO();
            req.setPriority(5);
            req.setPaymentMethodType("CARD");
            req.setCurrency("INR");
            req.setRetryNumber(1);
            req.setPreferredGateway("razorpay");

            GatewayRouteRule saved = rule(99L, "razorpay", null);
            when(routeRuleRepository.save(any())).thenReturn(saved);

            GatewayRouteRuleResponseDTO resp = service.createRouteRule(req);

            assertThat(resp.getPreferredGateway()).isEqualTo("razorpay");
            verify(routeRuleRepository).save(any(GatewayRouteRule.class));
        }
    }

    // ── updateRouteRule ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateRouteRule")
    class UpdateRouteRule {

        @Test
        @DisplayName("updates priority, gateway fields and active flag")
        void updatesFields() {
            GatewayRouteRule existing = rule(1L, "razorpay", "stripe");
            when(routeRuleRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(routeRuleRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            GatewayRouteRuleUpdateRequestDTO req = new GatewayRouteRuleUpdateRequestDTO();
            req.setPriority(20);
            req.setActive(false);

            GatewayRouteRuleResponseDTO resp = service.updateRouteRule(1L, req);

            assertThat(resp.getPriority()).isEqualTo(20);
            assertThat(resp.isActive()).isFalse();
        }

        @Test
        @DisplayName("throws ROUTE_RULE_NOT_FOUND for unknown id")
        void throwsOnMissingRule() {
            when(routeRuleRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateRouteRule(999L,
                    new GatewayRouteRuleUpdateRequestDTO()))
                    .isInstanceOf(RoutingException.class)
                    .extracting(t -> ((RoutingException) t).getErrorCode())
                    .isEqualTo("ROUTE_RULE_NOT_FOUND");
        }
    }
}
