package com.firstclub.membership.initializer;

import com.firstclub.membership.config.MembershipConfig;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.MembershipTier;
import com.firstclub.membership.entity.TierEligibilityCriteria;
import com.firstclub.membership.repository.MembershipPlanRepository;
import com.firstclub.membership.repository.MembershipTierRepository;
import com.firstclub.membership.repository.TierEligibilityCriteriaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Seeds membership tiers, plans and tier-eligibility criteria from
 * {@link MembershipConfig} on first startup.
 *
 * Idempotent — skips entirely when tiers already exist, so it is safe to
 * restart the application against a populated database.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class MembershipDataInitializer implements ApplicationRunner {

    private final MembershipTierRepository tierRepository;
    private final MembershipPlanRepository planRepository;
    private final TierEligibilityCriteriaRepository criteriaRepository;
    private final MembershipConfig membershipConfig;

    // Eligibility thresholds for GOLD and PLATINUM.
    // SILVER is the open default — no criteria row needed.
    private static final Map<String, int[]> ELIGIBILITY = Map.of(
        "GOLD",     new int[]{5,  2_000},
        "PLATINUM", new int[]{15, 5_000}
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (tierRepository.count() > 0) {
            log.info("Membership data already present — skipping initialisation.");
            return;
        }

        log.info("Initialising membership tiers and plans from configuration...");

        membershipConfig.getTiers().forEach((key, cfg) -> {
            MembershipTier tier = buildTier(key.toUpperCase(), cfg);
            MembershipTier saved = tierRepository.save(tier);
            createPlansForTier(saved, cfg.getBasePrice());
            createEligibilityCriteria(saved);
            log.info("Created tier '{}' (level {}) — base price: {}", saved.getName(), saved.getLevel(), cfg.getBasePrice());
        });

        log.info("Membership initialisation complete — {} tiers created.", membershipConfig.getTiers().size());
    }

    private MembershipTier buildTier(String name, MembershipConfig.TierConfig cfg) {
        return MembershipTier.builder()
                .name(name)
                .description(cfg.getDescription())
                .level(cfg.getLevel())
                .discountPercentage(cfg.getDiscountPercentage())
                .freeDelivery(cfg.isFreeDelivery())
                .exclusiveDeals(cfg.isExclusiveDeals())
                .earlyAccess(cfg.isEarlyAccess())
                .prioritySupport(cfg.isPrioritySupport())
                .maxCouponsPerMonth(cfg.getMaxCouponsPerMonth())
                .deliveryDays(cfg.getDeliveryDays())
                .additionalBenefits(cfg.getAdditionalBenefits())
                .build();
    }

    private void createPlansForTier(MembershipTier tier, BigDecimal basePrice) {
        MembershipConfig.PlanDiscounts discounts = membershipConfig.getPlanDiscounts();

        MembershipPlan monthly = MembershipPlan.builder()
                .name(tier.getName() + " Monthly")
                .description("Monthly " + tier.getName() + " membership")
                .type(MembershipPlan.PlanType.MONTHLY)
                .price(basePrice)
                .durationInMonths(1)
                .tier(tier)
                .isActive(true)
                .build();

        MembershipPlan quarterly = MembershipPlan.builder()
                .name(tier.getName() + " Quarterly")
                .description("Quarterly " + tier.getName() + " membership with savings")
                .type(MembershipPlan.PlanType.QUARTERLY)
                .price(basePrice.multiply(new BigDecimal("3")).multiply(discounts.getQuarterlyMultiplier()))
                .durationInMonths(3)
                .tier(tier)
                .isActive(true)
                .build();

        MembershipPlan yearly = MembershipPlan.builder()
                .name(tier.getName() + " Yearly")
                .description("Yearly " + tier.getName() + " membership with maximum savings")
                .type(MembershipPlan.PlanType.YEARLY)
                .price(basePrice.multiply(new BigDecimal("12")).multiply(discounts.getYearlyMultiplier()))
                .durationInMonths(12)
                .tier(tier)
                .isActive(true)
                .build();

        planRepository.saveAll(List.of(monthly, quarterly, yearly));
    }

    private void createEligibilityCriteria(MembershipTier tier) {
        int[] thresholds = ELIGIBILITY.get(tier.getName());
        if (thresholds == null) return; // SILVER — open to everyone

        TierEligibilityCriteria criteria = TierEligibilityCriteria.builder()
                .tier(tier)
                .minOrders(thresholds[0])
                .minMonthlySpend(new BigDecimal(thresholds[1]))
                .evaluationPeriodDays(30)
                .build();

        criteriaRepository.save(criteria);
        log.info("Created eligibility criteria for '{}' — minOrders={}, minSpend={}",
                tier.getName(), thresholds[0], thresholds[1]);
    }
}
