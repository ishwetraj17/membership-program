package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(name = "membership_tiers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "plans")
public class MembershipTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String description;

    /** 1 = Silver, 2 = Gold, 3 = Platinum */
    @Column(nullable = false)
    private Integer level;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercentage;

    @Column(nullable = false)
    private Boolean freeDelivery;

    @Column(nullable = false)
    private Boolean exclusiveDeals;

    @Column(nullable = false)
    private Boolean earlyAccess;

    @Column(nullable = false)
    private Boolean prioritySupport;

    @Column(nullable = false)
    private Integer maxCouponsPerMonth;

    @Column(nullable = false)
    private Integer deliveryDays;

    @Column(columnDefinition = "TEXT")
    private String additionalBenefits;

    // No cascade: plans have an independent lifecycle; tier deletion (if ever implemented)
    // must not silently drop plans. Plans are created and managed through PlanService.
    @OneToMany(mappedBy = "tier", fetch = FetchType.LAZY)
    private List<MembershipPlan> plans;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MembershipTier)) return false;
        MembershipTier that = (MembershipTier) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
