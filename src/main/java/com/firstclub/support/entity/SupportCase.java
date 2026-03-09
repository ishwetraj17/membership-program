package com.firstclub.support.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * An internal ops/support case that tracks an investigation linked to any
 * platform entity (customer, subscription, invoice, payment, refund,
 * dispute, or recon mismatch).
 *
 * <p>Lifecycle: OPEN → IN_PROGRESS → PENDING_CUSTOMER → RESOLVED → CLOSED.
 * Closed cases reject all mutations (notes, reassignment) to preserve audit
 * integrity.
 *
 * <p>Tenant isolation: every case belongs to exactly one merchant via
 * {@code merchantId}.
 */
@Entity
@Table(
    name = "support_cases",
    indexes = {
        @Index(name = "idx_sc_merchant_id",    columnList = "merchant_id"),
        @Index(name = "idx_sc_entity",         columnList = "linked_entity_type, linked_entity_id"),
        @Index(name = "idx_sc_merchant_status", columnList = "merchant_id, status"),
        @Index(name = "idx_sc_owner_user",     columnList = "owner_user_id")
    }
)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning merchant (tenant boundary). */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /**
     * Discriminator for the linked entity —
     * matches {@link com.firstclub.reporting.ops.timeline.entity.TimelineEntityTypes}
     * constants (CUSTOMER, SUBSCRIPTION, INVOICE, PAYMENT_INTENT, REFUND,
     * DISPUTE, RECON_MISMATCH, SUPPORT_CASE).
     */
    @Column(name = "linked_entity_type", nullable = false, length = 64)
    private String linkedEntityType;

    /** Primary-key ID of the linked entity in its own table. */
    @Column(name = "linked_entity_id", nullable = false)
    private Long linkedEntityId;

    /** Short human-readable description of the issue. */
    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private SupportCaseStatus status = SupportCaseStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private SupportCasePriority priority = SupportCasePriority.MEDIUM;

    /** Platform operator assigned as owner; null when unassigned. */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Convenience check: is this case no longer mutable? */
    public boolean isClosed() {
        return SupportCaseStatus.CLOSED == status;
    }
}
