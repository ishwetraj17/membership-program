package com.firstclub.catalog.entity;

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
 * A billable artifact specifying how a {@link Product} is charged.
 *
 * <p>A single product may have several prices — e.g. monthly INR 499 and
 * annual INR 4_999.  Each price is uniquely identified within the merchant
 * by its {@code price_code}.
 *
 * <p>Historical pricing changes are tracked via {@link PriceVersion} rather than
 * mutating the fields on this entity.  This preserves the commercial truth for
 * existing invoices/subscriptions.
 *
 * <p>{@link BillingType#RECURRING} prices must specify a positive
 * {@code billingIntervalCount}.  {@link BillingType#ONE_TIME} prices may omit
 * the interval fields (they default to 1 / MONTH but are not used for billing).
 */
@Entity
@Table(
    name = "prices",
    indexes = {
        @Index(name = "idx_prices_product_id", columnList = "product_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_price_merchant_code",
                columnNames = {"merchant_id", "price_code"})
    }
)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Price {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning merchant (tenant). Immutable after creation. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    @ToString.Exclude
    private MerchantAccount merchant;

    /** Parent product. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    @ToString.Exclude
    private Product product;

    /** Short, URL-safe identifier for the price.  Unique within the merchant. */
    @Column(name = "price_code", nullable = false, length = 64)
    private String priceCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_type", nullable = false, length = 32)
    private BillingType billingType;

    /** ISO 4217 currency code (e.g. INR, USD). */
    @Column(nullable = false, length = 10)
    private String currency;

    /** Base amount in the given currency.  Precision: 18,4. */
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_interval_unit", nullable = false, length = 16)
    @Builder.Default
    private BillingIntervalUnit billingIntervalUnit = BillingIntervalUnit.MONTH;

    /** Number of {@code billingIntervalUnit} units per billing cycle.  Must be ≥ 1. */
    @Column(name = "billing_interval_count", nullable = false)
    @Builder.Default
    private int billingIntervalCount = 1;

    /** Number of trial days before billing starts.  Zero disables the trial. */
    @Column(name = "trial_days", nullable = false)
    @Builder.Default
    private int trialDays = 0;

    /** Deactivated prices cannot be used for new subscriptions. */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Historical pricing snapshots for this price. */
    @OneToMany(mappedBy = "price", cascade = CascadeType.ALL, orphanRemoval = false)
    @Builder.Default
    @ToString.Exclude
    private List<PriceVersion> versions = new ArrayList<>();
}
