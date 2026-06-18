package com.firstclub.membership.service.benefit;

import com.firstclub.membership.entity.FeeType;
import com.firstclub.membership.entity.ProductCategory;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The outcome of evaluating a tier's benefit rules against a {@link CartContext}: the total goods
 * discount, which fees were waived, and human-readable descriptions of what applied. The original
 * fee amounts are retained so callers can present both the gross fee and the charged (post-waiver)
 * amount.
 */
@Getter
@Builder
public class BenefitEvaluation {

    private final BigDecimal discountAmount;
    private final Map<FeeType, BigDecimal> originalFees;
    private final Set<FeeType> waivedFees;
    private final List<String> appliedBenefits;
    /** Per-bucket discount breakdown, for attributing savings by type and category. */
    private final List<DiscountLine> discountLines;

    /**
     * One discount applied to one cart bucket. {@code category} is the goods bucket's category
     * (null = uncategorised); {@code categoryTargeted} indicates the winning rule was scoped to a
     * category (vs a whole-cart membership discount).
     */
    public record DiscountLine(ProductCategory category, boolean categoryTargeted, BigDecimal amount) {}

    public boolean isWaived(FeeType type) {
        return waivedFees.contains(type);
    }

    /** The fee actually charged after applying any waiver. */
    public BigDecimal chargedFee(FeeType type) {
        BigDecimal original = originalFees.getOrDefault(type, BigDecimal.ZERO);
        return isWaived(type) ? BigDecimal.ZERO : original;
    }

    public BigDecimal totalChargedFees() {
        BigDecimal sum = BigDecimal.ZERO;
        for (FeeType type : originalFees.keySet()) {
            sum = sum.add(chargedFee(type));
        }
        return sum;
    }

    /** A no-benefit result (no active membership) — nothing discounted, nothing waived. */
    public static BenefitEvaluation none(Map<FeeType, BigDecimal> fees) {
        Map<FeeType, BigDecimal> copy = new EnumMap<>(FeeType.class);
        copy.putAll(fees);
        return BenefitEvaluation.builder()
                .discountAmount(BigDecimal.ZERO)
                .originalFees(copy)
                .waivedFees(EnumSet.noneOf(FeeType.class))
                .appliedBenefits(List.of())
                .discountLines(List.of())
                .build();
    }
}
