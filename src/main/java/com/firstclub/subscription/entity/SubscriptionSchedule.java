package com.firstclub.subscription.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A future action queued against a {@link SubscriptionV2}. Each schedule entry
 * records what action to take, when to take it, and any structured payload
 * required to execute it (e.g. new price ID for a {@code CHANGE_PRICE} action).
 *
 * <p>Schedules are immutable after creation except for status transitions.
 * Once {@code EXECUTED} or {@code CANCELLED} they must not be modified.
 */
@Entity
@Table(
    name = "subscription_schedules",
    indexes = {
        @Index(name = "idx_sub_schedule_sub_id_effective_at",
               columnList = "subscription_id, effective_at")
    }
)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false)
    @ToString.Exclude
    private SubscriptionV2 subscription;

    @Enumerated(EnumType.STRING)
    @Column(name = "scheduled_action", nullable = false, length = 32)
    private SubscriptionScheduledAction scheduledAction;

    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    /**
     * JSON blob with action-specific details.
     * Examples:
     * <ul>
     *   <li>{@code CHANGE_PRICE} → {@code {"newPriceId": 42}}</li>
     *   <li>{@code PAUSE}        → {@code {"pauseEndsAt": "2026-06-01T00:00:00"}}</li>
     *   <li>{@code CANCEL}       → {@code {"reason": "customer_request"}}</li>
     * </ul>
     */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private SubscriptionScheduleStatus status = SubscriptionScheduleStatus.SCHEDULED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
