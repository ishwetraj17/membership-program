package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Audit log for all subscription state changes.
 *
 * Records every plan change, cancellation, renewal, upgrade, or downgrade
 * so that the full lifecycle of a subscription is traceable.
 */
@Entity
@Table(name = "subscription_history",
       indexes = @Index(name = "idx_sub_history_subscription_id", columnList = "subscription_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionHistory {

    public enum EventType {
        CREATED, UPGRADED, DOWNGRADED, CANCELLED, RENEWED, SUSPENDED, EXPIRED,
        /** Payment received — subscription transitioned from PENDING to ACTIVE. */
        PAYMENT_SUCCEEDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventType eventType;

    /** Plan before the change (null when the subscription was first created). */
    private Long oldPlanId;

    /** Plan after the change. */
    private Long newPlanId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Subscription.SubscriptionStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Subscription.SubscriptionStatus newStatus;

    @Column(length = 500)
    private String reason;

    /** ID of the user or system process that triggered the change. */
    private Long changedByUserId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime changedAt;
}
