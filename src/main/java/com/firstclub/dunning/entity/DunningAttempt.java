package com.firstclub.dunning.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "dunning_attempts", indexes = {
        @Index(name = "idx_dunning_subscription", columnList = "subscription_id"),
        @Index(name = "idx_dunning_due",          columnList = "scheduled_at, status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class DunningAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Plain FK — not a JPA association to keep the entity self-contained. */
    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DunningStatus status;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    // ── v2 policy-driven fields (null for legacy v1 attempts) ─────────────────

    /** FK to dunning_policies; null for v1 (pre-policy) attempts. */
    @Column(name = "dunning_policy_id")
    private Long dunningPolicyId;

    /** Payment method ID used for this attempt; null for legacy v1 attempts. */
    @Column(name = "payment_method_id")
    private Long paymentMethodId;

    /** True if this attempt used the backup payment method. */
    @Builder.Default
    @Column(name = "used_backup_method", nullable = false)
    private boolean usedBackupMethod = false;

    // ── Phase 16 — failure-code intelligence fields ───────────────────────────

    /** Raw failure code returned by the payment gateway (e.g. {@code "insufficient_funds"}). */
    @Column(name = "failure_code", length = 80)
    private String failureCode;

    /** Broad category derived from {@code failureCode} by {@code FailureCodeClassifier}. */
    @Column(name = "failure_category", length = 40)
    private String failureCategory;

    /** Decision taken by the strategy engine after classifying the failure. */
    @Column(name = "decision_taken", length = 30)
    private String decisionTaken;

    /** Human-readable explanation of the decision (stored for ops visibility). */
    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    /**
     * {@code true} when a non-retryable failure code caused this attempt to terminate
     * the entire dunning queue (stopped before the natural policy exhaustion point).
     */
    @Builder.Default
    @Column(name = "stopped_early", nullable = false)
    private boolean stoppedEarly = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum DunningStatus {
        SCHEDULED,
        SUCCESS,
        FAILED
    }
}
