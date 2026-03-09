package com.firstclub.payments.disputes.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A dispute (chargeback claim) raised against a captured payment.
 *
 * <p>Opening a dispute:
 * <ul>
 *   <li>Moves the payment to {@link com.firstclub.payments.entity.PaymentStatus#DISPUTED}.</li>
 *   <li>Reserves {@code amount} in {@code payment.disputedAmount}, reducing {@code netAmount}.</li>
 *   <li>Posts DR DISPUTE_RESERVE / CR PG_CLEARING to the ledger.</li>
 * </ul>
 *
 * <p>Tenant isolation: every dispute belongs to exactly one merchant. The service
 * validates that {@code payment.merchantId == merchantId} before any mutation.
 *
 * <p>Only one OPEN or UNDER_REVIEW dispute is allowed per payment at a time.
 */
@Entity
@Table(
    name = "disputes",
    indexes = {
        @Index(name = "idx_disputes_merchant_status", columnList = "merchant_id, status"),
        @Index(name = "idx_disputes_payment_id",      columnList = "payment_id")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    /** The customer who raised the dispute. */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    /** Structured reason code, e.g. FRAUDULENT_CHARGE, PRODUCT_NOT_RECEIVED. */
    @Column(name = "reason_code", nullable = false, length = 64)
    private String reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private DisputeStatus status = DisputeStatus.OPEN;

    @CreationTimestamp
    @Column(name = "opened_at", nullable = false, updatable = false)
    private LocalDateTime openedAt;

    /** Deadline for submitting evidence. Null means no deadline. */
    @Column(name = "due_by")
    private LocalDateTime dueBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /**
     * Set to {@code true} once {@code DisputeAccountingService.postDisputeOpen()} has
     * been called successfully.  Guards against double-posting the DISPUTE_RESERVE debit
     * on retry or repair-run scenarios.
     */
    @Column(name = "reserve_posted", nullable = false)
    @Builder.Default
    private boolean reservePosted = false;

    /**
     * Set to {@code true} once a WON or LOST accounting entry has been posted.
     * A second call to {@code resolveDispute()} will fail with
     * {@code DISPUTE_RESOLUTION_ALREADY_POSTED} when this flag is already {@code true}.
     */
    @Column(name = "resolution_posted", nullable = false)
    @Builder.Default
    private boolean resolutionPosted = false;
}
