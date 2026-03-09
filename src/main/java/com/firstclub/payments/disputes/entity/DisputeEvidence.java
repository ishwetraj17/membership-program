package com.firstclub.payments.disputes.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A piece of evidence submitted in support of a {@link Dispute}.
 *
 * <p>Immutable after creation — evidence cannot be edited or deleted once uploaded.
 * Submission is only allowed before the dispute's {@code due_by} deadline (if set).
 */
@Entity
@Table(
    name = "dispute_evidence",
    indexes = {
        @Index(name = "idx_dispute_evidence_dispute_id", columnList = "dispute_id")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class DisputeEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dispute_id", nullable = false)
    private Long disputeId;

    /**
     * Structured type, e.g. INVOICE, CORRESPONDENCE, DELIVERY_PROOF.
     * Used for display and retrieval filtering.
     */
    @Column(name = "evidence_type", nullable = false, length = 64)
    private String evidenceType;

    /**
     * A URI or key pointing to the stored evidence artefact (e.g., S3 object key,
     * document reference, or inline text excerpt). Not validated for format; the
     * caller is responsible for providing a meaningful reference.
     */
    @Column(name = "content_reference", nullable = false, columnDefinition = "TEXT")
    private String contentReference;

    /** The platform user (operator/admin) who uploaded this evidence. */
    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
