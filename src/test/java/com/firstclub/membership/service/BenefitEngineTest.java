package com.firstclub.membership.service;

import com.firstclub.membership.entity.BenefitRule;
import com.firstclub.membership.entity.BenefitType;
import com.firstclub.membership.entity.FeeType;
import com.firstclub.membership.entity.ProductCategory;
import com.firstclub.membership.repository.BenefitRuleRepository;
import com.firstclub.membership.service.benefit.BenefitEvaluation;
import com.firstclub.membership.service.benefit.CartContext;
import com.firstclub.membership.service.impl.BenefitEngineImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BenefitEngine — rule evaluation")
class BenefitEngineTest {

    private static final long TIER = 2L;

    @Mock private BenefitRuleRepository benefitRuleRepository;
    @InjectMocks private BenefitEngineImpl engine;

    private void rules(BenefitRule... rules) {
        when(benefitRuleRepository.findByTierIdAndActiveTrueOrderByPriorityDesc(TIER)).thenReturn(List.of(rules));
    }

    private BenefitRule discount(BigDecimal pct, ProductCategory category, String minCart, String cap) {
        return BenefitRule.builder()
                .benefitType(BenefitType.PERCENTAGE_DISCOUNT)
                .discountPercentage(pct)
                .productCategory(category)
                .minCartValue(minCart == null ? null : new BigDecimal(minCart))
                .maxDiscountAmount(cap == null ? null : new BigDecimal(cap))
                .active(true).build();
    }

    private BenefitRule waiver(BenefitType type, String minCart) {
        return BenefitRule.builder()
                .benefitType(type)
                .minCartValue(minCart == null ? null : new BigDecimal(minCart))
                .active(true).build();
    }

    private CartContext cart(String subtotal, Map<ProductCategory, BigDecimal> categories, Map<FeeType, BigDecimal> fees) {
        return CartContext.builder()
                .subtotal(new BigDecimal(subtotal))
                .categorySubtotals(categories)
                .fees(fees).build();
    }

    private Map<FeeType, BigDecimal> fees(FeeType type, String amount) {
        Map<FeeType, BigDecimal> map = new EnumMap<>(FeeType.class);
        map.put(type, new BigDecimal(amount));
        return map;
    }

    @Test @DisplayName("no rules → no discount, no waivers")
    void noRules() {
        rules();
        BenefitEvaluation e = engine.evaluate(TIER, cart("1000.00", Map.of(), fees(FeeType.DELIVERY, "49.00")));
        assertThat(e.getDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(e.getWaivedFees()).isEmpty();
        assertThat(e.chargedFee(FeeType.DELIVERY)).isEqualByComparingTo("49.00");
    }

    @Test @DisplayName("whole-cart percentage discount applies to the full subtotal")
    void wholeCartDiscount() {
        rules(discount(new BigDecimal("10.00"), null, null, null));
        BenefitEvaluation e = engine.evaluate(TIER, cart("1000.00", Map.of(), fees(FeeType.DELIVERY, "49.00")));
        assertThat(e.getDiscountAmount()).isEqualByComparingTo("100.00");
    }

    @Test @DisplayName("threshold edges: below excludes, at and above include (>=)")
    void thresholdEdges() {
        rules(discount(new BigDecimal("10.00"), null, "999", null));
        Map<FeeType, BigDecimal> noFees = new EnumMap<>(FeeType.class);

        assertThat(engine.evaluate(TIER, cart("998.00", Map.of(), noFees)).getDiscountAmount())
                .isEqualByComparingTo("0.00");
        assertThat(engine.evaluate(TIER, cart("999.00", Map.of(), noFees)).getDiscountAmount())
                .isEqualByComparingTo("99.90");
        assertThat(engine.evaluate(TIER, cart("1000.00", Map.of(), noFees)).getDiscountAmount())
                .isEqualByComparingTo("100.00");
    }

    @Test @DisplayName("overlapping discounts: each bucket gets the single best (no stacking)")
    void overlappingDiscounts() {
        // 10% whole-cart and 20% on beauty. Cart: 500 beauty + 500 electronics.
        rules(discount(new BigDecimal("10.00"), null, null, null),
              discount(new BigDecimal("20.00"), ProductCategory.BEAUTY, null, null));
        Map<ProductCategory, BigDecimal> categories = new EnumMap<>(ProductCategory.class);
        categories.put(ProductCategory.BEAUTY, new BigDecimal("500.00"));
        categories.put(ProductCategory.ELECTRONICS, new BigDecimal("500.00"));

        BenefitEvaluation e = engine.evaluate(TIER, cart("1000.00", categories, new EnumMap<>(FeeType.class)));
        // beauty: max(50, 100)=100 ; electronics: whole-cart 10% = 50 → total 150
        assertThat(e.getDiscountAmount()).isEqualByComparingTo("150.00");
    }

    @Test @DisplayName("category discount threshold is tested against the category bucket")
    void categoryThresholdEdge() {
        rules(discount(new BigDecimal("20.00"), ProductCategory.BEAUTY, "500", null));
        Map<ProductCategory, BigDecimal> below = new EnumMap<>(ProductCategory.class);
        below.put(ProductCategory.BEAUTY, new BigDecimal("499.00"));
        Map<ProductCategory, BigDecimal> at = new EnumMap<>(ProductCategory.class);
        at.put(ProductCategory.BEAUTY, new BigDecimal("500.00"));

        assertThat(engine.evaluate(TIER, cart("499.00", below, new EnumMap<>(FeeType.class))).getDiscountAmount())
                .isEqualByComparingTo("0.00");
        assertThat(engine.evaluate(TIER, cart("500.00", at, new EnumMap<>(FeeType.class))).getDiscountAmount())
                .isEqualByComparingTo("100.00");
    }

    @Test @DisplayName("discount cap limits the discount amount")
    void discountCap() {
        rules(discount(new BigDecimal("10.00"), null, null, "60"));
        BenefitEvaluation e = engine.evaluate(TIER, cart("1000.00", Map.of(), new EnumMap<>(FeeType.class)));
        assertThat(e.getDiscountAmount()).isEqualByComparingTo("60.00"); // 100 capped to 60
    }

    @Test @DisplayName("fee waiver applies only at/above its threshold")
    void feeWaiverThreshold() {
        rules(waiver(BenefitType.DELIVERY_FEE_WAIVER, "199"));
        assertThat(engine.evaluate(TIER, cart("198.00", Map.of(), fees(FeeType.DELIVERY, "49.00")))
                .isWaived(FeeType.DELIVERY)).isFalse();
        assertThat(engine.evaluate(TIER, cart("199.00", Map.of(), fees(FeeType.DELIVERY, "49.00")))
                .isWaived(FeeType.DELIVERY)).isTrue();
    }

    @Test @DisplayName("a fee is only waived when it is actually present on the cart")
    void waiverIgnoredWhenFeeAbsent() {
        rules(waiver(BenefitType.HANDLING_FEE_WAIVER, null));
        BenefitEvaluation e = engine.evaluate(TIER, cart("1000.00", Map.of(), fees(FeeType.DELIVERY, "49.00")));
        assertThat(e.getWaivedFees()).isEmpty();
    }

    @Test @DisplayName("multiple fee waivers can apply together")
    void multipleFeeWaivers() {
        rules(waiver(BenefitType.DELIVERY_FEE_WAIVER, null), waiver(BenefitType.HANDLING_FEE_WAIVER, null));
        Map<FeeType, BigDecimal> fees = new EnumMap<>(FeeType.class);
        fees.put(FeeType.DELIVERY, new BigDecimal("49.00"));
        fees.put(FeeType.HANDLING, new BigDecimal("10.00"));
        BenefitEvaluation e = engine.evaluate(TIER, cart("1000.00", Map.of(), fees));
        assertThat(e.getWaivedFees()).containsExactlyInAnyOrder(FeeType.DELIVERY, FeeType.HANDLING);
        assertThat(e.totalChargedFees()).isEqualByComparingTo("0.00");
    }
}
