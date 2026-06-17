package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * The tier a user has <em>earned</em> through their order activity — distinct from the
 * tier they have <em>purchased</em> via a subscription plan.
 *
 * One row per user (current earned tier). Recomputed on demand and by a scheduled job
 * from {@link com.firstclub.membership.service.TierEvaluationService}.
 */
@Entity
@Table(name = "user_tier_assignment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"user", "tier"})
public class UserTierAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tier_id", nullable = false)
    private MembershipTier tier;

    /** How the tier was assigned — AUTO (engine) or MANUAL (admin override). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    /** When the eligibility engine last evaluated this user. */
    @Column(nullable = false)
    private LocalDateTime evaluatedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public enum Source { AUTO, MANUAL }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserTierAssignment that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
