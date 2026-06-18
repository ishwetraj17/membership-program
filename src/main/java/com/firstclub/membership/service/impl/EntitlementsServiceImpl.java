package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.EntitlementsDTO;
import com.firstclub.membership.entity.BenefitRule;
import com.firstclub.membership.entity.MembershipTier;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.repository.BenefitRuleRepository;
import com.firstclub.membership.repository.OrderRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.service.EntitlementsService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cache-first, fail-open entitlements provider.
 *
 * Read path: cache → DB → (on any failure) safe non-member fallback. The cache is consulted and
 * populated explicitly (rather than via {@code @Cacheable}) so we can (a) emit precise hit/miss
 * metrics across any cache backend, and (b) tolerate a cache outage by falling through to the DB,
 * and a DB outage by returning a non-member response — so checkout is never blocked by membership.
 */
@Service
@Slf4j
public class EntitlementsServiceImpl implements EntitlementsService {

    static final String CACHE = "entitlements";

    private final SubscriptionRepository subscriptionRepository;
    private final OrderRepository orderRepository;
    private final BenefitRuleRepository benefitRuleRepository;
    private final CacheManager cacheManager;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public EntitlementsServiceImpl(SubscriptionRepository subscriptionRepository,
                                   OrderRepository orderRepository,
                                   BenefitRuleRepository benefitRuleRepository,
                                   CacheManager cacheManager,
                                   MeterRegistry meterRegistry,
                                   Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.orderRepository = orderRepository;
        this.benefitRuleRepository = benefitRuleRepository;
        this.cacheManager = cacheManager;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    public EntitlementsDTO getEntitlements(Long userId) {
        try {
            Cache cache = cacheManager.getCache(CACHE);

            // 1) Cache (primary). A cache outage must not fail the lookup — fall through to DB.
            if (cache != null) {
                try {
                    EntitlementsDTO cached = cache.get(userId, EntitlementsDTO.class);
                    if (cached != null) {
                        meterRegistry.counter("membership.entitlements.cache", "result", "hit").increment();
                        return cached;
                    }
                    meterRegistry.counter("membership.entitlements.cache", "result", "miss").increment();
                } catch (RuntimeException cacheError) {
                    meterRegistry.counter("membership.entitlements.cache", "result", "error").increment();
                    log.warn("Entitlements cache read failed for user {} — falling back to DB", userId, cacheError);
                }
            }

            // 2) Database (fallback / source of truth).
            EntitlementsDTO fresh = loadFromDb(userId);

            // 3) Repopulate the cache (best-effort).
            if (cache != null) {
                try {
                    cache.put(userId, fresh);
                } catch (RuntimeException cacheError) {
                    meterRegistry.counter("membership.entitlements.cache", "result", "error").increment();
                    log.warn("Entitlements cache write failed for user {}", userId, cacheError);
                }
            }
            meterRegistry.counter("membership.entitlements.lookup", "outcome", "ok").increment();
            return fresh;

        } catch (RuntimeException e) {
            // DB unavailable (or any other failure): never block checkout — return a safe default.
            meterRegistry.counter("membership.entitlements.lookup", "outcome", "fallback").increment();
            log.error("Entitlements lookup failed for user {} — returning non-member fallback", userId, e);
            return nonMember(userId, true);
        }
    }

    @Override
    public void invalidate(Long userId) {
        meterRegistry.counter("membership.entitlements.invalidation").increment();
        Cache cache = cacheManager.getCache(CACHE);
        if (cache != null) {
            try {
                cache.evict(userId);
            } catch (RuntimeException e) {
                log.warn("Entitlements cache eviction failed for user {}", userId, e);
            }
        }
    }

    @Override
    public void invalidateAll() {
        meterRegistry.counter("membership.entitlements.invalidation", "scope", "all").increment();
        Cache cache = cacheManager.getCache(CACHE);
        if (cache != null) {
            try {
                cache.clear();
            } catch (RuntimeException e) {
                log.warn("Entitlements cache clear failed", e);
            }
        }
    }

    private EntitlementsDTO loadFromDb(Long userId) {
        Optional<Subscription> active = subscriptionRepository.findActiveByUserId(userId, LocalDateTime.now(clock));
        BigDecimal savings = orderRepository.totalSavings(userId);
        if (savings == null) savings = BigDecimal.ZERO;

        if (active.isEmpty()) {
            EntitlementsDTO nonMember = nonMember(userId, false);
            nonMember.setTotalSavings(savings);
            return nonMember;
        }

        Subscription sub = active.get();
        MembershipTier tier = sub.getPlan().getTier();
        LocalDateTime now = LocalDateTime.now(clock);

        // Fee waivers and pricing benefits are derived from the tier's configured benefit rules so
        // entitlements reflect exactly what the checkout engine will apply (thresholds, categories,
        // fee waivers). Non-pricing perks remain on the tier's flat flags.
        List<BenefitRule> rules = benefitRuleRepository.findByTierIdAndActiveTrueOrderByPriorityDesc(tier.getId());

        List<String> feeWaivers = rules.stream()
                .filter(r -> r.getBenefitType().isFeeWaiver())
                .map(r -> r.getBenefitType().waivedFee().orElseThrow().name())
                .distinct().sorted().toList();

        List<String> benefits = new ArrayList<>();
        for (BenefitRule rule : rules) {
            benefits.add(describeRule(rule));
        }
        if (Boolean.TRUE.equals(tier.getExclusiveDeals())) benefits.add("Exclusive deals");
        if (Boolean.TRUE.equals(tier.getEarlyAccess())) benefits.add("Early sale access");
        if (Boolean.TRUE.equals(tier.getPrioritySupport())) benefits.add("Priority support");
        benefits.add(tier.getMaxCouponsPerMonth() + " coupons/month");

        return EntitlementsDTO.builder()
                .userId(userId)
                .member(true)
                .subscriptionStatus(sub.getStatus().name())
                .plan(sub.getPlan().getName())
                .tier(tier.getName())
                .tierLevel(tier.getLevel())
                .discountPercentage(tier.getDiscountPercentage())
                .freeDelivery(Boolean.TRUE.equals(tier.getFreeDelivery()))
                .prioritySupport(Boolean.TRUE.equals(tier.getPrioritySupport()))
                .exclusiveDeals(Boolean.TRUE.equals(tier.getExclusiveDeals()))
                .earlyAccess(Boolean.TRUE.equals(tier.getEarlyAccess()))
                .maxCouponsPerMonth(tier.getMaxCouponsPerMonth())
                .deliveryDays(tier.getDeliveryDays())
                .feeWaivers(feeWaivers)
                .benefits(benefits)
                .membershipExpiry(sub.getEndDate().toString())
                .daysRemaining(Math.max(0, ChronoUnit.DAYS.between(now, sub.getEndDate())))
                .totalSavings(savings)
                .fallback(false)
                .build();
    }

    /** Human-readable description of a configured benefit rule for the entitlements contract. */
    private String describeRule(BenefitRule rule) {
        String threshold = rule.getMinCartValue() != null
                ? " above ₹" + rule.getMinCartValue().stripTrailingZeros().toPlainString() : "";
        if (rule.getBenefitType().isFeeWaiver()) {
            return "Free " + rule.getBenefitType().waivedFee().orElseThrow().name() + threshold;
        }
        String pct = rule.getDiscountPercentage() != null
                ? rule.getDiscountPercentage().stripTrailingZeros().toPlainString() : "0";
        String scope = rule.getProductCategory() != null ? rule.getProductCategory().name() : "all items";
        return pct + "% off " + scope + threshold;
    }

    private EntitlementsDTO nonMember(Long userId, boolean fallback) {
        return EntitlementsDTO.builder()
                .userId(userId)
                .member(false)
                .discountPercentage(BigDecimal.ZERO)
                .freeDelivery(false)
                .prioritySupport(false)
                .exclusiveDeals(false)
                .earlyAccess(false)
                .feeWaivers(List.of())
                .benefits(List.of())
                .daysRemaining(0)
                .totalSavings(BigDecimal.ZERO)
                .fallback(fallback)
                .build();
    }
}
