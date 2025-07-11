package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
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
@Table(name = "subscriptions")
@Data
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
    private User user;

    // Which plan they subscribed to
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
        PENDING     // Payment pending
    }

    /**
     * Check if subscription is currently usable
     * 
     * NOTE: This might need optimization for large datasets - consider caching
     */
    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE && 
               LocalDateTime.now().isBefore(endDate);
    }

    /**
     * Check if subscription has expired
     */
    public boolean isExpired() {
        return status == SubscriptionStatus.EXPIRED || 
               LocalDateTime.now().isAfter(endDate);
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