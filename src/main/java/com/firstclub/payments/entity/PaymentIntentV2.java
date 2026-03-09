package com.firstclub.payments.entity;

import com.firstclub.customer.entity.Customer;
import com.firstclub.merchant.entity.MerchantAccount;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A versioned payment intent that tracks the full lifecycle of a payment,
 * including all retry attempts.
 *
 * <p>Idempotency: callers must supply an {@code idempotency_key} on creation
 * and confirm operations.  If an intent with the same key already exists, the
 * existing intent is returned without side-effects.
 *
 * <p>Security: raw card data is never stored here.  Only a reference to a
 * tokenised {@link PaymentMethod} is held.
 */
@Entity
@Table(
    name = "payment_intents_v2",
    indexes = {
        @Index(name = "idx_pi_v2_merchant_customer_status",
               columnList = "merchant_id, customer_id, status"),
        @Index(name = "idx_pi_v2_invoice",    columnList = "invoice_id"),
        @Index(name = "idx_pi_v2_status_created", columnList = "status, created_at")
    }
)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentIntentV2 {

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

    // ── Relations ────────────────────────────────────────────────────────────

    /** Optional: the invoice this payment is fulfilling. */
    @Column(name = "invoice_id")
    private Long invoiceId;

    /** Optional: the subscription this payment belongs to. */
    @Column(name = "subscription_id")
    private Long subscriptionId;

    /** Optional attached payment instrument (required before confirm). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_method_id")
    @ToString.Exclude
    private PaymentMethod paymentMethod;

    // ── Money ────────────────────────────────────────────────────────────────

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    private String currency;

    // ── State ────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private PaymentIntentStatusV2 status = PaymentIntentStatusV2.REQUIRES_PAYMENT_METHOD;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_mode", nullable = false, length = 16)
    @Builder.Default
    private CaptureMode captureMode = CaptureMode.AUTO;

    // ── Idempotency ──────────────────────────────────────────────────────────

    /**
     * Cryptographically random secret shared with the client.
     * Used to authenticate client-side polling and 3DS callbacks.
     */
    @Column(name = "client_secret", nullable = false, unique = true, length = 128)
    private String clientSecret;

    /**
     * Caller-supplied idempotency key.  If a second create request arrives with
     * the same key, the original intent is returned unchanged.
     */
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    // ── Misc ─────────────────────────────────────────────────────────────────

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    /**
     * Optimistic-locking version.  Incremented on every state transition.
     */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    // ── Timestamps ───────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── Phase 8: Reconciliation fields ───────────────────────────────────────

    /**
     * Foreign key to the single successful {@link PaymentAttempt} that caused
     * this intent to transition to {@code SUCCEEDED}.
     * Null until the intent succeeds.
     */
    @Column(name = "last_successful_attempt_id")
    private Long lastSuccessfulAttemptId;

    /**
     * Tracks whether this intent has outstanding UNKNOWN attempts awaiting
     * async gateway status resolution.
     * Values: {@code "PENDING"} (unresolved), {@code "RESOLVED"} (all resolved), or null.
     */
    @Column(name = "reconciliation_state", length = 32)
    private String reconciliationState;

    // ── Phase 14: Settlement / FX fields ─────────────────────────────────────

    /**
     * ISO-4217 currency code in which the merchant receives settlement.
     * May differ from {@link #currency} in cross-border payment scenarios.
     */
    @Column(name = "settlement_currency", length = 3)
    private String settlementCurrency;

    /**
     * Settlement amount expressed in the smallest denomination of
     * {@link #settlementCurrency} (e.g. paisa, cents).
     */
    @Column(name = "settlement_amount_minor")
    private Long settlementAmountMinor;

    /**
     * Exchange rate applied at settlement: 1 unit of {@link #currency}
     * equals {@code fxRate} units of {@link #settlementCurrency}.
     * Null for domestic (same-currency) payments.
     */
    @Column(name = "fx_rate", precision = 18, scale = 8)
    private java.math.BigDecimal fxRate;

    /**
     * Timestamp at which the FX rate was locked by the gateway.
     * Null for domestic payments or when not yet settled.
     */
    @Column(name = "fx_rate_captured_at")
    private LocalDateTime fxRateCapturedAt;

    // ── Associations ─────────────────────────────────────────────────────────

    @OneToMany(mappedBy = "paymentIntent", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("attemptNumber ASC")
    @Builder.Default
    @ToString.Exclude
    private List<PaymentAttempt> attempts = new ArrayList<>();
}
