package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Entity
@Table(name = "membership_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"tier", "subscriptions"})
public class MembershipPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanType type;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer durationInMonths;

    @Column(nullable = false)
    private Boolean isActive;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tier_id", nullable = false)
    private MembershipTier tier;

    // No cascade: subscriptions are core business records with their own lifecycle.
    // A plan deletion (if implemented) must never silently delete subscription history.
    @OneToMany(mappedBy = "plan", fetch = FetchType.LAZY)
    private List<Subscription> subscriptions;

    public enum PlanType { MONTHLY, QUARTERLY, YEARLY }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MembershipPlan)) return false;
        MembershipPlan that = (MembershipPlan) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    public BigDecimal getMonthlyPrice() {
        return price.divide(new BigDecimal(durationInMonths), 2, RoundingMode.HALF_UP);
    }

    public BigDecimal calculateSavings(BigDecimal monthlyPrice) {
        return monthlyPrice.multiply(new BigDecimal(durationInMonths)).subtract(price);
    }
}