package com.firstclub.membership.initializer;

import com.firstclub.membership.config.MembershipConfig;
import com.firstclub.membership.entity.Benefit;
import com.firstclub.membership.entity.Coupon;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.MembershipTier;
import com.firstclub.membership.entity.TierBenefit;
import com.firstclub.membership.entity.TierEligibilityCriteria;
import com.firstclub.membership.repository.BenefitRepository;
import com.firstclub.membership.repository.CouponRepository;
import com.firstclub.membership.repository.MembershipPlanRepository;
import com.firstclub.membership.repository.MembershipTierRepository;
import com.firstclub.membership.repository.TierBenefitRepository;
import com.firstclub.membership.repository.TierEligibilityCriteriaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
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
    private final BenefitRepository benefitRepository;
    private final TierBenefitRepository tierBenefitRepository;
    private final CouponRepository couponRepository;
    private final MembershipConfig membershipConfig;

    // Eligibility thresholds for GOLD and PLATINUM.
    // SILVER is the open default — no criteria row needed.
    private static final Map<String, int[]> ELIGIBILITY = Map.of(
        "GOLD",     new int[]{5,  2_000},
        "PLATINUM", new int[]{15, 5_000}
    );

    // PLATINUM additionally requires cohort membership (invite-only tier).
    private static final Map<String, String> COHORT_CODES = Map.of(
        "PLATINUM", "PREMIUM_COHORT"
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (tierRepository.count() > 0) {
            log.info("Membership data already present — skipping initialisation.");
            return;
        }

        log.info("Initialising membership tiers and plans from configuration...");

        Map<String, Benefit> catalog = seedBenefitCatalog();

        membershipConfig.getTiers().forEach((key, cfg) -> {
            MembershipTier tier = buildTier(key.toUpperCase(), cfg);
            MembershipTier saved = tierRepository.save(tier);
            createPlansForTier(saved, cfg.getBasePrice());
            createEligibilityCriteria(saved);
            attachBenefits(saved, cfg, catalog);
            log.info("Created tier '{}' (level {}) — base price: {}", saved.getName(), saved.getLevel(), cfg.getBasePrice());
        });

        seedDemoCoupon();

        log.info("Membership initialisation complete — {} tiers created.", membershipConfig.getTiers().size());
    }

    /** Seeds a demo coupon so the redemption flow works out of the box. */
    private void seedDemoCoupon() {
        if (couponRepository.existsByCode("WELCOME10")) return;
        couponRepository.save(Coupon.builder()
                .code("WELCOME10")
                .description("10% off your order — one per member")
                .discountType(Coupon.DiscountType.PERCENT)
                .discountValue(new BigDecimal("10"))
                .perUserLimit(1)
                .active(true)
                .build());
        log.info("Seeded demo coupon WELCOME10");
    }

    /** Seeds the canonical, configurable benefit catalog (once). */
    private Map<String, Benefit> seedBenefitCatalog() {
        Map<String, Benefit> catalog = new LinkedHashMap<>();
        catalog.put("EXTRA_DISCOUNT", benefit("EXTRA_DISCOUNT", "Extra discount", "Additional discount on eligible items", Benefit.Category.DISCOUNT));
        catalog.put("FREE_DELIVERY", benefit("FREE_DELIVERY", "Free delivery", "Free delivery on eligible orders", Benefit.Category.DELIVERY));
        catalog.put("FAST_DELIVERY", benefit("FAST_DELIVERY", "Fast delivery", "Reduced delivery SLA", Benefit.Category.DELIVERY));
        catalog.put("EXCLUSIVE_DEALS", benefit("EXCLUSIVE_DEALS", "Exclusive deals", "Access to members-only deals", Benefit.Category.ACCESS));
        catalog.put("EARLY_ACCESS", benefit("EARLY_ACCESS", "Early sale access", "Early access to sales and drops", Benefit.Category.ACCESS));
        catalog.put("PRIORITY_SUPPORT", benefit("PRIORITY_SUPPORT", "Priority support", "Priority customer support queue", Benefit.Category.SUPPORT));
        catalog.put("MONTHLY_COUPONS", benefit("MONTHLY_COUPONS", "Monthly coupons", "Coupons granted each month", Benefit.Category.REWARDS));
        return catalog;
    }

    private Benefit benefit(String code, String name, String description, Benefit.Category category) {
        return benefitRepository.save(Benefit.builder()
                .code(code).name(name).description(description).category(category).build());
    }

    /** Attaches the configured benefits to a tier based on its feature flags and values. */
    private void attachBenefits(MembershipTier tier, MembershipConfig.TierConfig cfg, Map<String, Benefit> catalog) {
        link(tier, catalog.get("EXTRA_DISCOUNT"), cfg.getDiscountPercentage().stripTrailingZeros().toPlainString() + "%");
        if (cfg.isFreeDelivery()) link(tier, catalog.get("FREE_DELIVERY"), null);
        link(tier, catalog.get("FAST_DELIVERY"), cfg.getDeliveryDays() + "-day SLA");
        if (cfg.isExclusiveDeals()) link(tier, catalog.get("EXCLUSIVE_DEALS"), null);
        if (cfg.isEarlyAccess()) link(tier, catalog.get("EARLY_ACCESS"), null);
        if (cfg.isPrioritySupport()) link(tier, catalog.get("PRIORITY_SUPPORT"), null);
        link(tier, catalog.get("MONTHLY_COUPONS"), cfg.getMaxCouponsPerMonth() + "/month");
    }

    private void link(MembershipTier tier, Benefit benefit, String value) {
        tierBenefitRepository.save(TierBenefit.builder().tier(tier).benefit(benefit).value(value).build());
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
                .cohortCode(COHORT_CODES.get(tier.getName()))
                .evaluationPeriodDays(30)
                .build();

        criteriaRepository.save(criteria);
        log.info("Created eligibility criteria for '{}' — minOrders={}, minSpend={}",
                tier.getName(), thresholds[0], thresholds[1]);
    }
}
