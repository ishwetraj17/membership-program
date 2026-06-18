package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A configurable acquisition offer that discounts the FIRST billing period of a new subscription —
 * e.g. ₹1 first month, 50% off first month, or a free first month. Applied at subscription creation
 * and charged through the normal payment flow; renewals always bill the full plan price.
 */
@Entity
@Table(name = "introductory_offers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntroductoryOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "offer_type", nullable = false, length = 20)
    private OfferType offerType;

    /** FIXED_PRICE: the first-period price (e.g. 1.00). PERCENT_OFF: percent (e.g. 50.00). FREE: ignored. */
    @Column(name = "offer_value", precision = 10, scale = 2)
    private BigDecimal value;

    /** Restrict the offer to one plan; null = applies to any plan. */
    @Column(name = "plan_id")
    private Long planId;

    @Column(nullable = false)
    private boolean active;

    public enum OfferType { FIXED_PRICE, PERCENT_OFF, FREE }

    /** The first-period price for this offer applied to a plan's full price (never negative). */
    public BigDecimal firstPeriodPrice(BigDecimal fullPrice) {
        BigDecimal price = switch (offerType) {
            case FREE -> BigDecimal.ZERO;
            case FIXED_PRICE -> value != null ? value : BigDecimal.ZERO;
            case PERCENT_OFF -> {
                BigDecimal pct = value != null ? value : BigDecimal.ZERO;
                BigDecimal discount = fullPrice.multiply(pct).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                yield fullPrice.subtract(discount);
            }
        };
        return price.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IntroductoryOffer that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
