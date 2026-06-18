package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Append-only ledger of realised member savings — one row per saved amount, attributed to a type
 * and (where relevant) a product category, and tied to its source (an order or a subscription).
 *
 * This is the auditable source of truth for the savings tracker: the lifetime/monthly totals and
 * the by-type / by-category breakdowns are all aggregates over these rows, and every figure can be
 * traced back to the order or subscription that produced it.
 */
@Entity
@Table(name = "savings_ledger", indexes = {
        @Index(name = "idx_savings_user", columnList = "user_id"),
        @Index(name = "idx_savings_user_occurred", columnList = "user_id, occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SavingsLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "savings_type", nullable = false, length = 30)
    private SavingsType savingsType;

    /** Product category for CATEGORY_DISCOUNT savings; null otherwise. */
    @Enumerated(EnumType.STRING)
    @Column(name = "product_category", length = 30)
    private ProductCategory productCategory;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** What produced the saving: ORDER or SUBSCRIPTION. */
    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SavingsLedgerEntry that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
