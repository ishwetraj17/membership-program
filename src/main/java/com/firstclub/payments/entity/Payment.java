package com.firstclub.payments.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Check(name = "chk_payment_capacity",
        constraints = "captured_amount_minor >= refunded_amount_minor + disputed_amount_minor")
@Check(name = "chk_payment_amounts_non_negative",
        constraints = "captured_amount_minor >= 0 AND refunded_amount_minor >= 0 AND disputed_amount_minor >= 0")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_intent_id", nullable = false)
    private Long paymentIntentId;

    /** Optional — set for merchant-scoped payments (all new captures). */
    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentStatus status;

    @Column(name = "gateway_txn_id", unique = true, nullable = false, length = 64)
    private String gatewayTxnId;

    // ── Phase 11: amount tracking columns ─────────────────────────────────

    /** Gross amount captured at the gateway. */
    @Builder.Default
    @Column(name = "captured_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal capturedAmount = BigDecimal.ZERO;

    /** Cumulative amount refunded so far (sum of all COMPLETED refund_v2 rows). */
    @Builder.Default
    @Column(name = "refunded_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    /** Amount under dispute/chargeback (reserved, not refundable). */
    @Builder.Default
    @Column(name = "disputed_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal disputedAmount = BigDecimal.ZERO;

    /** capturedAmount - refundedAmount - disputedAmount (recalculated on every refund). */
    @Builder.Default
    @Column(name = "net_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal netAmount = BigDecimal.ZERO;

    // ── Phase 9: minor-unit integer columns for DB-level capacity constraint ──

    /**
     * capturedAmount expressed in minor units (amount × 10_000).
     * Kept in sync by {@code PaymentCapacityInvariantService.syncMinorUnitFields}.
     * Participates in {@code chk_payment_capacity} CHECK constraint.
     */
    @Builder.Default
    @Column(name = "captured_amount_minor", nullable = false)
    private long capturedAmountMinor = 0L;

    /** refundedAmount expressed in minor units. */
    @Builder.Default
    @Column(name = "refunded_amount_minor", nullable = false)
    private long refundedAmountMinor = 0L;

    /** disputedAmount expressed in minor units. */
    @Builder.Default
    @Column(name = "disputed_amount_minor", nullable = false)
    private long disputedAmountMinor = 0L;

    @Column(name = "captured_at")
    private LocalDateTime capturedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
