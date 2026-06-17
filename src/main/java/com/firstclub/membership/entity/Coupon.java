package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A redeemable discount coupon — a configurable, usage-limited perk that members actually
 * redeem at checkout (as opposed to the tier discount/free-delivery which apply automatically).
 */
@Entity
@Table(name = "coupons")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DiscountType discountType;

    /** Percent (e.g. 10 = 10%) or flat amount, per {@link #discountType}. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    /** Total redemptions allowed across all users (null = unlimited). */
    @Column
    private Integer maxRedemptions;

    /** Redemptions allowed per user (null = unlimited). */
    @Column
    private Integer perUserLimit;

    @Column(nullable = false)
    private boolean active;

    @Column
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum DiscountType { PERCENT, FLAT }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Coupon that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
