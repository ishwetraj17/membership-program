package com.firstclub.dunning.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Merchant-configurable dunning policy.
 *
 * <p>Controls the retry schedule, grace window, backup payment method usage,
 * and terminal outcome when all retries are exhausted.
 *
 * <p>{@code retry_offsets_json} encodes a JSON array of positive integers
 * representing the delay in <em>minutes</em> from the moment the renewal
 * payment failed.  Example: {@code [60, 360, 1440, 4320]} → retry after
 * 1 h, 6 h, 24 h, 3 d.
 */
@Entity
@Table(
    name = "dunning_policies",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_dunning_policy_merchant_code",
        columnNames = {"merchant_id", "policy_code"}
    ),
    indexes = @Index(name = "idx_dunning_policies_merchant", columnList = "merchant_id")
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class DunningPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Plain FK — tenant isolation enforced at service layer. */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /**
     * Short slug identifying the policy within a merchant.
     * The special value {@code DEFAULT} is used as the merchant's default policy.
     */
    @Column(name = "policy_code", nullable = false, length = 64)
    private String policyCode;

    /**
     * JSON array of delay offsets in <em>minutes</em> from the failed charge.
     * Example: {@code [60, 360, 1440, 4320]} for 1 h / 6 h / 24 h / 3 d.
     */
    @Column(name = "retry_offsets_json", nullable = false, columnDefinition = "TEXT")
    private String retryOffsetsJson;

    /** Maximum number of retry attempts to schedule. Must be &gt; 0. */
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    /**
     * Number of days from initial failure within which retries are attempted.
     * Attempts whose scheduled time would exceed this window are not created.
     */
    @Column(name = "grace_days", nullable = false)
    private int graceDays;

    /**
     * When {@code true}, DunningServiceV2 may attempt the backup payment
     * method after the primary payment method fails.
     */
    @Builder.Default
    @Column(name = "fallback_to_backup_payment_method", nullable = false)
    private boolean fallbackToBackupPaymentMethod = false;

    /** Subscription status to apply when all retries are exhausted. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status_after_exhaustion", nullable = false, length = 32)
    private DunningTerminalStatus statusAfterExhaustion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
