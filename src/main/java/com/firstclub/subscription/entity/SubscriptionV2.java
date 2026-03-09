package com.firstclub.subscription.entity;

import com.firstclub.catalog.entity.Price;
import com.firstclub.catalog.entity.PriceVersion;
import com.firstclub.catalog.entity.Product;
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
 * A subscription contract (generation 2) that links a customer to a
 * specific product/price combination within a merchant's scope.
 *
 * <p>Unlike the legacy {@link com.firstclub.membership.entity.Subscription},
 * this entity is price-version-aware: the exact {@link PriceVersion} active at
 * subscription creation is captured and never changes mid-cycle.
 *
 * <p>Tenant isolation: every query must include {@code merchantId}.
 *
 * <p>Optimistic locking via {@code @Version} prevents concurrent state-machine
 * transitions racing each other.
 */
@Entity
@Table(
    name = "subscriptions_v2",
    indexes = {
        @Index(name = "idx_sub_v2_merchant_customer_status",
               columnList = "merchant_id, customer_id, status"),
        @Index(name = "idx_sub_v2_merchant_next_billing",
               columnList = "merchant_id, next_billing_at, status")
    }
)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionV2 {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Tenant ──────────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    @ToString.Exclude
    private MerchantAccount merchant;

    // ── References ──────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    @ToString.Exclude
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    @ToString.Exclude
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_id", nullable = false)
    @ToString.Exclude
    private Price price;

    /** The exact price snapshot captured at subscription creation. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_version_id", nullable = false)
    @ToString.Exclude
    private PriceVersion priceVersion;

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private SubscriptionStatusV2 status = SubscriptionStatusV2.INCOMPLETE;

    // ── Billing anchors ─────────────────────────────────────────────────────

    /** Fixed reference point used to calculate billing cycle boundaries. */
    @Column(name = "billing_anchor_at", nullable = false)
    private LocalDateTime billingAnchorAt;

    @Column(name = "current_period_start")
    private LocalDateTime currentPeriodStart;

    @Column(name = "current_period_end")
    private LocalDateTime currentPeriodEnd;

    @Column(name = "next_billing_at")
    private LocalDateTime nextBillingAt;

    // ── Cancel ──────────────────────────────────────────────────────────────

    @Column(name = "cancel_at_period_end", nullable = false)
    @Builder.Default
    private boolean cancelAtPeriodEnd = false;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    // ── Pause ───────────────────────────────────────────────────────────────

    @Column(name = "pause_starts_at")
    private LocalDateTime pauseStartsAt;

    @Column(name = "pause_ends_at")
    private LocalDateTime pauseEndsAt;

    // ── Trial ───────────────────────────────────────────────────────────────

    @Column(name = "trial_ends_at")
    private LocalDateTime trialEndsAt;

    // ── Metadata ────────────────────────────────────────────────────────────

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    // ── Optimistic locking ──────────────────────────────────────────────────

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    // ── Timestamps ──────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── Child schedules ─────────────────────────────────────────────────────

    @OneToMany(mappedBy = "subscription", cascade = CascadeType.ALL, orphanRemoval = false)
    @Builder.Default
    @ToString.Exclude
    private List<SubscriptionSchedule> schedules = new ArrayList<>();
}
