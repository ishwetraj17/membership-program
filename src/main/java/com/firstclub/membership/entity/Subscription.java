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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"user", "plan"})
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private MembershipPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status;

    @Column(nullable = false)
    private LocalDateTime startDate;

    @Column(nullable = false)
    private LocalDateTime endDate;

    @Column(nullable = false)
    private LocalDateTime nextBillingDate;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal paidAmount;

    @Column(nullable = false)
    private Boolean autoRenewal;

    @Column
    private LocalDateTime cancelledAt;

    @Column
    private String cancellationReason;

    /**
     * Optimistic-locking version — Hibernate increments this on every UPDATE.
     * If two concurrent transactions read the same row, only the first to commit
     * will succeed; the second throws {@link jakarta.persistence.OptimisticLockException}.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public enum SubscriptionStatus {
        ACTIVE, EXPIRED, CANCELLED, SUSPENDED, PENDING
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Subscription)) return false;
        Subscription that = (Subscription) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE && LocalDateTime.now().isBefore(endDate);
    }

    public boolean isExpired() {
        return status == SubscriptionStatus.EXPIRED || LocalDateTime.now().isAfter(endDate);
    }

    public long getDaysRemaining() {
        if (isExpired()) return 0;
        return ChronoUnit.DAYS.between(LocalDateTime.now(), endDate);
    }
}
