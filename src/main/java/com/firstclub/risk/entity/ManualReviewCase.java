package com.firstclub.risk.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Represents a manual review case created when a risk decision returns {@link RiskAction#REVIEW}.
 *
 * <p>Lifecycle:  OPEN → APPROVED | REJECTED | ESCALATED | CLOSED
 *               ESCALATED → APPROVED | REJECTED | CLOSED
 * APPROVED, REJECTED, CLOSED are terminal states.
 */
@Entity
@Table(name = "manual_review_cases", indexes = {
        @Index(name = "idx_review_cases_status", columnList = "status")
})
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManualReviewCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "payment_intent_id", nullable = false)
    private Long paymentIntentId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private ReviewCaseStatus status = ReviewCaseStatus.OPEN;

    /** User ID of the reviewer assigned to this case (nullable). */
    @Column(name = "assigned_to")
    private Long assignedTo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── Phase 18: SLA, escalation, and close audit fields ─────────────────────
    /** Deadline by which this case must be resolved (set on creation). */
    @Column(name = "sla_due_at")
    private LocalDateTime slaDueAt;

    /** Timestamp when the case was transitioned to ESCALATED. */
    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    /** Human-readable reason for the final approve/reject/close decision. */
    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    /** User ID (or 0 for system) that closed/approved/rejected the case. */
    @Column(name = "closed_by")
    private Long closedBy;

    /** Timestamp of the terminal transition (APPROVED / REJECTED / CLOSED). */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}
