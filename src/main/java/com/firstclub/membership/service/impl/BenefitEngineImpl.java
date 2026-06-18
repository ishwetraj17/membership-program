package com.firstclub.membership.service.impl;

import com.firstclub.membership.entity.BenefitRule;
import com.firstclub.membership.entity.BenefitType;
import com.firstclub.membership.entity.FeeType;
import com.firstclub.membership.entity.ProductCategory;
import com.firstclub.membership.repository.BenefitRuleRepository;
import com.firstclub.membership.service.BenefitEngine;
import com.firstclub.membership.service.benefit.BenefitEvaluation;
import com.firstclub.membership.service.benefit.CartContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rule-driven benefit engine.
 *
 * <p><b>Discounts.</b> The cart is partitioned into buckets — one per recognised product category
 * plus an implicit "other" bucket for uncategorised goods. Each bucket gets the single best
 * applicable percentage discount (no stacking on the same goods), choosing between:
 * <ul>
 *   <li>whole-cart rules (no category), whose threshold is tested against the full subtotal; and</li>
 *   <li>category rules matching the bucket, whose threshold is tested against the bucket's value.</li>
 * </ul>
 * Bucket discounts are summed. A rule's {@code maxDiscountAmount} caps its contribution per bucket.
 *
 * <p><b>Fee waivers.</b> A fee is waived if any active waiver rule of its type qualifies (threshold
 * tested against the goods subtotal).
 */
@Service
@RequiredArgsConstructor
public class BenefitEngineImpl implements BenefitEngine {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final BenefitRuleRepository benefitRuleRepository;

    @Override
    @Transactional(readOnly = true)
    public BenefitEvaluation evaluate(Long tierId, CartContext cart) {
        List<BenefitRule> rules = benefitRuleRepository.findByTierIdAndActiveTrueOrderByPriorityDesc(tierId);
        List<String> applied = new ArrayList<>();
        List<BenefitEvaluation.DiscountLine> lines = new ArrayList<>();

        BigDecimal discount = computeDiscount(rules, cart, applied, lines);
        Set<FeeType> waived = computeWaivers(rules, cart, applied);

        return BenefitEvaluation.builder()
                .discountAmount(discount)
                .originalFees(copyFees(cart.getFees()))
                .waivedFees(waived)
                .appliedBenefits(applied)
                .discountLines(lines)
                .build();
    }

    // ── Discounts ───────────────────────────────────────────────────────────
    private BigDecimal computeDiscount(List<BenefitRule> rules, CartContext cart, List<String> applied,
                                       List<BenefitEvaluation.DiscountLine> lines) {
        BigDecimal subtotal = cart.getSubtotal();
        Map<ProductCategory, BigDecimal> byCategory = cart.getCategorySubtotals();

        // Recognised category buckets, plus an "other" bucket for everything else.
        BigDecimal categorised = byCategory.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal other = subtotal.subtract(categorised);

        BigDecimal total = BigDecimal.ZERO;

        for (Map.Entry<ProductCategory, BigDecimal> bucket : byCategory.entrySet()) {
            total = total.add(bestBucketDiscount(rules, subtotal, bucket.getKey(), bucket.getValue(), applied, lines));
        }
        if (other.signum() > 0) {
            total = total.add(bestBucketDiscount(rules, subtotal, null, other, applied, lines));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** Best single discount for one bucket; records the winning benefit's description and line. */
    private BigDecimal bestBucketDiscount(List<BenefitRule> rules, BigDecimal subtotal,
                                          ProductCategory bucketCategory, BigDecimal bucketValue,
                                          List<String> applied, List<BenefitEvaluation.DiscountLine> lines) {
        BigDecimal best = BigDecimal.ZERO;
        BenefitRule bestRule = null;

        for (BenefitRule rule : rules) {
            if (rule.getBenefitType() != BenefitType.PERCENTAGE_DISCOUNT) continue;

            boolean wholeCart = rule.getProductCategory() == null;
            boolean categoryMatch = bucketCategory != null && rule.getProductCategory() == bucketCategory;
            if (!wholeCart && !categoryMatch) continue;

            // Whole-cart rules gate on the full subtotal; category rules gate on the bucket.
            BigDecimal thresholdBase = wholeCart ? subtotal : bucketValue;
            if (rule.getMinCartValue() != null && thresholdBase.compareTo(rule.getMinCartValue()) < 0) continue;

            BigDecimal amount = applyCap(percentage(bucketValue, rule.getDiscountPercentage()), rule.getMaxDiscountAmount());
            if (amount.compareTo(best) > 0) {
                best = amount;
                bestRule = rule;
            }
        }
        if (bestRule != null && best.signum() > 0) {
            applied.add(describeDiscount(bestRule, bucketCategory, best));
            lines.add(new BenefitEvaluation.DiscountLine(bucketCategory, bestRule.getProductCategory() != null, best));
        }
        return best;
    }

    /** EnumMap's copy constructor rejects an empty plain map, so build by class and copy. */
    private Map<FeeType, BigDecimal> copyFees(Map<FeeType, BigDecimal> fees) {
        Map<FeeType, BigDecimal> copy = new EnumMap<>(FeeType.class);
        copy.putAll(fees);
        return copy;
    }

    private BigDecimal percentage(BigDecimal base, BigDecimal pct) {
        if (pct == null) return BigDecimal.ZERO;
        return base.multiply(pct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal applyCap(BigDecimal amount, BigDecimal cap) {
        return (cap != null && amount.compareTo(cap) > 0) ? cap : amount;
    }

    private String describeDiscount(BenefitRule rule, ProductCategory bucketCategory, BigDecimal amount) {
        String pct = rule.getDiscountPercentage().stripTrailingZeros().toPlainString();
        String scope = (rule.getProductCategory() != null) ? rule.getProductCategory().name() : "cart";
        if (rule.getProductCategory() == null && bucketCategory != null) {
            scope = bucketCategory.name(); // whole-cart rule applied to this category bucket
        }
        return pct + "% off " + scope + " (−" + amount + ")";
    }

    // ── Fee waivers ─────────────────────────────────────────────────────────
    private Set<FeeType> computeWaivers(List<BenefitRule> rules, CartContext cart, List<String> applied) {
        Set<FeeType> waived = EnumSet.noneOf(FeeType.class);
        BigDecimal subtotal = cart.getSubtotal();

        for (BenefitRule rule : rules) {
            if (!rule.getBenefitType().isFeeWaiver()) continue;
            FeeType fee = rule.getBenefitType().waivedFee().orElseThrow();
            if (waived.contains(fee)) continue;

            // Only waive a fee that is actually present on this cart.
            BigDecimal feeAmount = cart.getFees().get(fee);
            if (feeAmount == null || feeAmount.signum() <= 0) continue;

            if (rule.getMinCartValue() != null && subtotal.compareTo(rule.getMinCartValue()) < 0) continue;

            waived.add(fee);
            applied.add("Waived " + fee.name() + " fee (−" + feeAmount.setScale(2, RoundingMode.HALF_UP) + ")");
        }
        return waived;
    }
}
