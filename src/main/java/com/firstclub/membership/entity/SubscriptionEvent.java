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
 * Append-only billing / audit log of subscription lifecycle changes.
 *
 * Each row captures the money movement (if any) and a snapshot of plan/tier at the time,
 * denormalised (raw ids, not relations) so the log is independent of mutable subscription
 * state and cheap to query. This is the source of truth for lifetime revenue — the
 * subscription row only ever holds the <em>current</em> period's {@code paidAmount}.
 */
@Entity
@Table(name = "subscription_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long subscriptionId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType eventType;

    /** Money movement for this event (charge ≥ 0; non-billing events are 0). */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column
    private Long planId;

    @Column
    private String tierName;

    /** Payment provider reference for charging events (null for non-billing events). */
    @Column(name = "payment_reference")
    private String paymentReference;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Outbox aggregate-type for subscription events. */
    public static final String AGGREGATE = "SUBSCRIPTION";

    public enum EventType {
        CREATED, RENEWED, UPGRADED, DOWNGRADED, CANCELLED, EXPIRED, REFUNDED,
        TRIAL_STARTED, TRIAL_CONVERTED, TRIAL_EXPIRED
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SubscriptionEvent that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
