package com.firstclub.merchant.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Per-merchant configuration that drives billing, settlement, and dunning behaviour.
 *
 * <p>Automatically created (with sensible defaults) whenever a new
 * {@link MerchantAccount} is created.  Always exactly one row per merchant.
 */
@Entity
@Table(name = "merchant_settings")
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", nullable = false, unique = true)
    @ToString.Exclude
    private MerchantAccount merchant;

    /** Whether outbound webhooks are sent to the merchant's configured callback URL. */
    @Column(name = "webhook_enabled", nullable = false)
    @Builder.Default
    private Boolean webhookEnabled = Boolean.TRUE;

    /** How often captured payments are swept to the merchant's bank account. */
    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_frequency", nullable = false, length = 32)
    @Builder.Default
    private SettlementFrequency settlementFrequency = SettlementFrequency.DAILY;

    /** Whether the dunning engine automatically retries failed renewal payments. */
    @Column(name = "auto_retry_enabled", nullable = false)
    @Builder.Default
    private Boolean autoRetryEnabled = Boolean.TRUE;

    /** Grace period (days) before a PAST_DUE subscription moves to SUSPENDED. */
    @Column(name = "default_grace_days", nullable = false)
    @Builder.Default
    private Integer defaultGraceDays = 7;

    /** Optional dunning policy code — references a policy template (future phase). */
    @Column(name = "default_dunning_policy_code", length = 64)
    private String defaultDunningPolicyCode;

    /** Free-form JSON for merchant-specific flags (extensible without schema changes). */
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
