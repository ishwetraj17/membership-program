package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Subscription entity tracking user memberships
 * 
 * Links users to their chosen membership plans and tracks
 * subscription lifecycle (active, expired, cancelled).
 * 
 * Implemented by Shwet Raj
 */
@Entity
@Table(name = "subscriptions", indexes = {
    @Index(name = "idx_subscription_status",          columnList = "status"),
    @Index(name = "idx_subscription_end_date",         columnList = "end_date"),
    @Index(name = "idx_subscription_user_id",          columnList = "user_id"),
    @Index(name = "idx_sub_user_status_end",           columnList = "user_id, status, end_date")
})
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Which user owns this subscription
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    // Which plan they subscribed to
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    @ToString.Exclude
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

    // Amount paid in INR
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal paidAmount;

    @Column(nullable = false)
    private Boolean autoRenewal;

    // Cancellation tracking
    @Column
    private LocalDateTime cancelledAt;

    @Column
    private String cancellationReason;

    // ── Managed-renewal billing fields ────────────────────────────────────────

    /**
     * When true the subscription will be cancelled (not renewed) at the end of
     * the current billing period instead of auto-renewing.
     */
    @Builder.Default
    @Column(name = "cancel_at_period_end", nullable = false)
    private Boolean cancelAtPeriodEnd = false;

    /**
     * End of the grace period granted after a failed renewal payment.
     * Null when the subscription is not in dunning.
     */
    @Column(name = "grace_until")
    private LocalDateTime graceUntil;

    /**
     * If set, the subscription's benefits are suspended until this timestamp
     * (e.g. voluntary pause by the member).
     */
    @Column(name = "paused_until")
    private LocalDateTime pausedUntil;

    /**
     * Timestamp at which the next auto-renewal should be attempted.
     * Set to the subscription's {@code endDate} on creation and advanced by
     * {@link com.firstclub.dunning.service.RenewalService} after each successful
     * renewal.  {@code null} for subscriptions not managed by the renewal engine.
     */
    @Column(name = "next_renewal_at")
    private LocalDateTime nextRenewalAt;

    /**
     * Optimistic-locking version field.
     * Prevents lost-update races in concurrent upgrade/cancel operations:
     * if two requests read the same version and both try to write, the
     * second write will throw OptimisticLockException instead of silently
     * overwriting the first change.
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

    /**
     * Subscription lifecycle states
     */
    public enum SubscriptionStatus {
        ACTIVE,     // Currently active and valid
        EXPIRED,    // Past end date but not cancelled
        CANCELLED,  // User cancelled before expiry
        SUSPENDED,  // Temporarily disabled
        PENDING,    // Payment pending
        PAST_DUE    // Payment failed; overdue
    }

    /**
     * Check if subscription is currently usable.
     * Returns true only when status is ACTIVE and not yet past end date.
     * Does NOT return true for CANCELLED subscriptions, even if past end date.
     */
    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE &&
               LocalDateTime.now().isBefore(endDate);
    }

    /**
     * Check if subscription has expired by scheduler action.
     * Only returns true for the EXPIRED status — not for CANCELLED records
     * that happen to be past their end date.
     */
    public boolean isExpired() {
        return status == SubscriptionStatus.EXPIRED;
    }

    /**
     * Check if the current time is past the subscription end date,
     * regardless of terminal status. Used by the scheduler to find
     * candidates for bulk expiry.
     */
    public boolean isPastEndDate() {
        return LocalDateTime.now().isAfter(endDate);
    }

    /**
     * Get remaining days in subscription
     * 
     * Returns 0 if expired/cancelled
     */
    public long getDaysRemaining() {
        if (isExpired()) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.now(), endDate);
    }
}