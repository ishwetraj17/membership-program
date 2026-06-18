package com.firstclub.membership.service;

import com.firstclub.membership.dto.EntitlementsDTO;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.MembershipTier;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.repository.BenefitRuleRepository;
import com.firstclub.membership.repository.OrderRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.service.impl.EntitlementsServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EntitlementsService — cache-first, fail-open")
class EntitlementsServiceTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private BenefitRuleRepository benefitRuleRepository;
    @Mock private CacheManager cacheManager;
    @Mock private Cache cache;

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
    private EntitlementsServiceImpl service;

    private Subscription activeSilver;

    @BeforeEach
    void setUp() {
        service = new EntitlementsServiceImpl(subscriptionRepository, orderRepository,
                benefitRuleRepository, cacheManager, new SimpleMeterRegistry(), clock);

        MembershipTier silver = MembershipTier.builder()
                .id(1L).name("SILVER").level(1).discountPercentage(new BigDecimal("5.00"))
                .freeDelivery(false).exclusiveDeals(false).earlyAccess(false).prioritySupport(false)
                .maxCouponsPerMonth(2).deliveryDays(5).additionalBenefits("x").build();
        MembershipPlan silverMonthly = MembershipPlan.builder()
                .id(1L).name("Silver Monthly").description("d").type(MembershipPlan.PlanType.MONTHLY)
                .price(new BigDecimal("299.00")).durationInMonths(1).isActive(true).tier(silver).build();
        activeSilver = Subscription.builder()
                .id(100L).plan(silverMonthly).status(Subscription.SubscriptionStatus.ACTIVE)
                .startDate(LocalDateTime.now(clock)).endDate(LocalDateTime.now(clock).plusMonths(1))
                .paidAmount(new BigDecimal("299.00")).autoRenewal(true).version(0L).build();
    }

    @Test @DisplayName("cache hit — returns cached value without touching the DB")
    void cacheHit() {
        EntitlementsDTO cached = EntitlementsDTO.builder().userId(1L).member(true).tier("GOLD").build();
        when(cacheManager.getCache("entitlements")).thenReturn(cache);
        when(cache.get(1L, EntitlementsDTO.class)).thenReturn(cached);

        EntitlementsDTO result = service.getEntitlements(1L);

        assertThat(result).isSameAs(cached);
        verify(subscriptionRepository, never()).findActiveByUserId(any(), any());
    }

    @Test @DisplayName("cache miss — loads from DB and populates the cache")
    void cacheMiss() {
        when(cacheManager.getCache("entitlements")).thenReturn(cache);
        when(cache.get(1L, EntitlementsDTO.class)).thenReturn(null);
        when(subscriptionRepository.findActiveByUserId(eq(1L), any())).thenReturn(Optional.of(activeSilver));
        when(orderRepository.totalSavings(1L)).thenReturn(new BigDecimal("120.00"));
        when(benefitRuleRepository.findByTierIdAndActiveTrueOrderByPriorityDesc(1L)).thenReturn(List.of());

        EntitlementsDTO result = service.getEntitlements(1L);

        assertThat(result.isMember()).isTrue();
        assertThat(result.getTier()).isEqualTo("SILVER");
        assertThat(result.getDiscountPercentage()).isEqualByComparingTo("5.00");
        assertThat(result.getTotalSavings()).isEqualByComparingTo("120.00");
        verify(cache).put(eq(1L), any(EntitlementsDTO.class));
    }

    @Test @DisplayName("Redis unavailable — cache read throws, falls through to the DB")
    void redisUnavailable() {
        when(cacheManager.getCache("entitlements")).thenReturn(cache);
        when(cache.get(1L, EntitlementsDTO.class)).thenThrow(new RuntimeException("redis down"));
        when(subscriptionRepository.findActiveByUserId(eq(1L), any())).thenReturn(Optional.of(activeSilver));
        when(orderRepository.totalSavings(1L)).thenReturn(BigDecimal.ZERO);
        when(benefitRuleRepository.findByTierIdAndActiveTrueOrderByPriorityDesc(1L)).thenReturn(List.of());

        EntitlementsDTO result = service.getEntitlements(1L);

        assertThat(result.isMember()).isTrue();
        assertThat(result.isFallback()).isFalse();
        verify(subscriptionRepository).findActiveByUserId(eq(1L), any());
    }

    @Test @DisplayName("DB unavailable — returns a safe non-member fallback (never throws)")
    void dbUnavailable() {
        when(cacheManager.getCache("entitlements")).thenReturn(cache);
        when(cache.get(1L, EntitlementsDTO.class)).thenReturn(null);
        when(subscriptionRepository.findActiveByUserId(eq(1L), any()))
                .thenThrow(new RuntimeException("db down"));

        EntitlementsDTO result = service.getEntitlements(1L);

        assertThat(result.isMember()).isFalse();
        assertThat(result.isFallback()).isTrue();
        assertThat(result.getDiscountPercentage()).isEqualByComparingTo("0");
    }

    @Test @DisplayName("invalidate evicts the user's cached entitlements")
    void invalidate() {
        when(cacheManager.getCache("entitlements")).thenReturn(cache);
        service.invalidate(1L);
        verify(cache).evict(1L);
    }
}
