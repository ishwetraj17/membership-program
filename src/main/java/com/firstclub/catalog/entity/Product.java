package com.firstclub.catalog.entity;

import com.firstclub.merchant.entity.MerchantAccount;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A saleable concept belonging to a single merchant (tenant).
 *
 * <p>A {@code Product} is the high-level offering — e.g. "Gold Membership" or
 * "Annual SaaS Plan".  Pricing details live in the associated {@link Price}
 * entities; a product may have multiple prices (e.g. monthly vs annual
 * variants).
 *
 * <p>Tenant isolation: {@code (merchant_id, product_code)} is unique.
 *
 * <p>Lifecycle: ACTIVE → ARCHIVED.  Archived products cannot be referenced by
 * new subscriptions.  No un-archive path by design — create a new product
 * if the offering is relaunched.
 */
@Entity
@Table(
    name = "products",
    indexes = {
        @Index(name = "idx_products_merchant_status", columnList = "merchant_id, status")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_product_merchant_code",
                columnNames = {"merchant_id", "product_code"})
    }
)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning merchant (tenant). Immutable after creation. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    @ToString.Exclude
    private MerchantAccount merchant;

    /** Short, URL-safe identifier for the product.  Unique within the merchant. */
    @Column(name = "product_code", nullable = false, length = 64)
    private String productCode;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private ProductStatus status = ProductStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Prices defined for this product. */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = false)
    @Builder.Default
    @ToString.Exclude
    private List<Price> prices = new ArrayList<>();
}
