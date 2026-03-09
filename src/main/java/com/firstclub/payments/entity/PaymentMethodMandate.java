package com.firstclub.payments.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A bank/gateway mandate linked to a {@link PaymentMethod}.
 *
 * <p>A mandate authorises the platform to auto-debit up to {@code maxAmount}
 * without requiring explicit customer approval on each transaction.  Used for
 * recurring subscriptions via NACH, eMandate, or recurring card authorities.
 *
 * <p>Mandates start in {@link MandateStatus#PENDING}, transition to
 * {@link MandateStatus#ACTIVE} when the bank approves, and can be revoked
 * at any time by the merchant or customer.
 */
@Entity
@Table(
    name = "payment_method_mandates",
    indexes = {
        @Index(name = "idx_payment_method_mandates_pm_id",
               columnList = "payment_method_id")
    }
)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentMethodMandate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_method_id", nullable = false)
    @ToString.Exclude
    private PaymentMethod paymentMethod;

    /** Gateway-issued reference for this mandate registration. */
    @Column(name = "mandate_reference", nullable = false, length = 128)
    private String mandateReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private MandateStatus status = MandateStatus.PENDING;

    /** Maximum per-transaction debit amount authorised by this mandate. */
    @Column(name = "max_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal maxAmount;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    /** Set when the bank/gateway confirms mandate approval. */
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** Set when this mandate is revoked. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
