package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * A configurable commerce benefit attached to a membership tier — the unit the benefit engine
 * evaluates at checkout. Everything that varies by business policy is data here, so new benefits
 * (thresholds, category targeting, fee waivers) are configured by admins without code changes.
 *
 * <ul>
 *   <li>{@code benefitType} — what the rule grants (a discount, or a specific fee waiver).</li>
 *   <li>{@code minCartValue} — threshold the relevant base must reach (null = no threshold).</li>
 *   <li>{@code productCategory} — restricts a discount to one category (null = whole cart).</li>
 *   <li>{@code discountPercentage} / {@code maxDiscountAmount} — for PERCENTAGE_DISCOUNT only.</li>
 *   <li>{@code priority} — tie-breaker / ordering hint when rules overlap (higher first).</li>
 * </ul>
 */
@Entity
@Table(name = "benefit_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "tier")
public class BenefitRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tier_id", nullable = false)
    private MembershipTier tier;

    @Enumerated(EnumType.STRING)
    @Column(name = "benefit_type", nullable = false, length = 40)
    private BenefitType benefitType;

    /** Discount scope; null means the rule applies to the whole cart. Ignored for fee waivers. */
    @Enumerated(EnumType.STRING)
    @Column(name = "product_category", length = 30)
    private ProductCategory productCategory;

    /** Minimum qualifying value of the rule's base; null means it always applies. */
    @Column(name = "min_cart_value", precision = 10, scale = 2)
    private BigDecimal minCartValue;

    /** For PERCENTAGE_DISCOUNT: the percentage off (e.g. 10.00). */
    @Column(name = "discount_percentage", precision = 5, scale = 2)
    private BigDecimal discountPercentage;

    /** For PERCENTAGE_DISCOUNT: optional cap on the discount amount. */
    @Column(name = "max_discount_amount", precision = 10, scale = 2)
    private BigDecimal maxDiscountAmount;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean active;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BenefitRule that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
