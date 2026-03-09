package com.firstclub.payments.entity;

import com.firstclub.customer.entity.Customer;
import com.firstclub.merchant.entity.MerchantAccount;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A reusable, tokenized payment instrument belonging to a customer.
 *
 * <p><strong>Security:</strong> raw card PAN, CVV, or full account numbers are
 * <em>never</em> stored here.  Only the opaque {@code providerToken} issued by
 * the payment gateway/tokenization service is persisted, alongside non-sensitive
 * display metadata ({@code last4}, {@code brand}).
 *
 * <p>Tenant isolation: every query must include {@code merchantId}.
 */
@Entity
@Table(
    name = "payment_methods",
    indexes = {
        @Index(name = "idx_payment_methods_merchant_customer",
               columnList = "merchant_id, customer_id, status"),
        @Index(name = "idx_payment_methods_customer_default",
               columnList = "customer_id, is_default")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_payment_methods_provider_token",
                columnNames = {"provider", "provider_token"})
    }
)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Tenant ──────────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    @ToString.Exclude
    private MerchantAccount merchant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    @ToString.Exclude
    private Customer customer;

    // ── Instrument ──────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "method_type", nullable = false, length = 32)
    private PaymentMethodType methodType;

    /**
     * Opaque tokenized reference from the payment provider.
     * <b>Never a raw PAN.</b>  Must be unique per provider.
     */
    @Column(name = "provider_token", nullable = false, length = 255)
    private String providerToken;

    /** Stable fingerprint/hash used to identify duplicate instruments without
     *  exposing the token itself. Optional. */
    @Column(name = "fingerprint", length = 255)
    private String fingerprint;

    /** Last 4 digits (card) or partial identifier — display only. */
    @Column(name = "last4", length = 8)
    private String last4;

    /** Card scheme or wallet brand (Visa, Mastercard, Paytm, etc.). */
    @Column(name = "brand", length = 64)
    private String brand;

    /** Gateway / tokenization provider name (e.g. "razorpay", "stripe"). */
    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    // ── Status and default ───────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private PaymentMethodStatus status = PaymentMethodStatus.ACTIVE;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    // ── Timestamps ───────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── Associations ─────────────────────────────────────────────────────────

    @OneToMany(mappedBy = "paymentMethod", cascade = CascadeType.ALL)
    @Builder.Default
    @ToString.Exclude
    private List<PaymentMethodMandate> mandates = new ArrayList<>();
}
