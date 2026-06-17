package com.firstclub.membership.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Externalized membership configuration.
 *
 * All pricing, discount multipliers and tier attributes are driven by
 * application.yml — no code change required to update business rules.
 *
 * Populated via {@code membership.*} properties.
 */
@ConfigurationProperties(prefix = "membership")
@Data
public class MembershipConfig {

    /**
     * Tier configurations keyed by lowercase tier name (silver / gold / platinum).
     * Insertion order is preserved so tiers are created in YAML order.
     */
    private Map<String, TierConfig> tiers = new LinkedHashMap<>();

    private PlanDiscounts planDiscounts = new PlanDiscounts();

    /**
     * When true, subscription creation enforces tier eligibility (the user must qualify for the
     * plan's tier per {@code TierEvaluationService}). Off by default — see README for rationale.
     */
    private boolean enforceTierEligibility = false;

    /** When true, cancelling an active subscription refunds the unused (pro-rated) portion. */
    private boolean refundOnCancel = false;

    @Data
    public static class TierConfig {
        private int level;
        private String description;
        private BigDecimal basePrice;
        private BigDecimal discountPercentage;
        private boolean freeDelivery;
        private boolean exclusiveDeals;
        private boolean earlyAccess;
        private boolean prioritySupport;
        private int maxCouponsPerMonth;
        private int deliveryDays;
        private String additionalBenefits;
    }

    @Data
    public static class PlanDiscounts {
        private BigDecimal quarterlyMultiplier = new BigDecimal("0.95");
        private BigDecimal yearlyMultiplier = new BigDecimal("0.85");
    }
}
