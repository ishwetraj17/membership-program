package com.firstclub.payments.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Records a refund against a previously captured {@link Payment}.
 *
 * <p>A corresponding {@link com.firstclub.ledger.entity.LedgerEntry} of type
 * {@code REFUND_ISSUED} is created atomically by {@link com.firstclub.payments.service.RefundService}.
 */
@Entity
@Table(name = "refunds",
        indexes = @Index(name = "idx_refunds_payment_id", columnList = "payment_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@link Payment#getId()}. */
    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String currency = "INR";

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RefundStatus status = RefundStatus.COMPLETED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
