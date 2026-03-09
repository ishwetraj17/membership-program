package com.firstclub.payments.refund.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A partial or full refund issued against a {@link com.firstclub.payments.entity.Payment}.
 *
 * <p>Unlike the legacy {@code refunds} table, this table tracks:
 * <ul>
 *   <li>Merchant scoping ({@code merchant_id}) for tenant isolation.</li>
 *   <li>A structured {@code reason_code} for categorisation and reporting.</li>
 *   <li>An optional {@code refund_reference} for gateway correlation.</li>
 *   <li>A two-phase status: {@code PENDING → COMPLETED | FAILED}.</li>
 * </ul>
 *
 * <p>Each completed refund atomically increments
 * {@code payments.refunded_amount} and re-derives {@code payments.net_amount}
 * within the same transaction, preventing over-refund even under concurrency.
 */
@Entity
@Table(
    name = "refunds_v2",
    indexes = {
        @Index(name = "idx_refunds_v2_payment_id",  columnList = "payment_id"),
        @Index(name = "idx_refunds_v2_merchant_id", columnList = "merchant_id")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class RefundV2 {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    /** Optional link to the invoice that was refunded (null for gateway-only refunds). */
    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "reason_code", nullable = false, length = 64)
    private String reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private RefundV2Status status = RefundV2Status.PENDING;

    /** Gateway or internal correlation reference (optional). */
    @Column(name = "refund_reference", length = 128)
    private String refundReference;

    /**
     * SHA-256 idempotency fingerprint.  Set by {@code RefundServiceV2Impl} from the
     * caller-supplied value or auto-generated as {@code SHA-256(merchantId:paymentId:amount:reasonCode)}.
     * A UNIQUE partial index on this column prevents duplicate refunds from concurrent requests
     * even when the DB pessimistic lock is momentarily absent (e.g. read-replica fan-out).
     */
    @Column(name = "request_fingerprint", length = 255)
    private String requestFingerprint;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
