package com.firstclub.dunning.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Per-subscription payment method configuration for the dunning engine.
 *
 * <p>Links a subscription to its primary (and optional backup) payment method.
 * DunningServiceV2 consults this record to determine which payment method to
 * charge on each retry attempt.
 *
 * <p>One row per subscription (enforced by {@code UNIQUE subscription_id}).
 * Created or replaced via the {@code PUT /payment-preferences} endpoint.
 */
@Entity
@Table(
    name = "subscription_payment_preferences",
    indexes = @Index(name = "idx_sub_payment_pref_sub", columnList = "subscription_id")
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class SubscriptionPaymentPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Plain FK to subscriptions_v2.id. Unique: one record per subscription. */
    @Column(name = "subscription_id", nullable = false, unique = true)
    private Long subscriptionId;

    /** Payment method used on the first retry attempt. */
    @Column(name = "primary_payment_method_id", nullable = false)
    private Long primaryPaymentMethodId;

    /**
     * Optional fallback payment method.  Used only when:
     * <ol>
     *   <li>The primary method fails, and
     *   <li>The dunning policy has {@code fallback_to_backup_payment_method = true}.
     * </ol>
     */
    @Column(name = "backup_payment_method_id")
    private Long backupPaymentMethodId;

    /**
     * Optional JSON array encoding the explicit retry order of payment method IDs.
     * Reserved for future use; not enforced by the current engine.
     */
    @Column(name = "retry_order_json", columnDefinition = "TEXT")
    private String retryOrderJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
