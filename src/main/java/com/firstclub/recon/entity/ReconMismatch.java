package com.firstclub.recon.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "recon_mismatches", indexes = {
        @Index(name = "idx_recon_mismatches_report", columnList = "report_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ReconMismatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private MismatchType type;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReconMismatchStatus status = ReconMismatchStatus.OPEN;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
