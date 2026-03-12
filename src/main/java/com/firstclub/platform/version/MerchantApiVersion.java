package com.firstclub.platform.version;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * JPA entity for the {@code merchant_api_versions} table (created in V68).
 *
 * <p>Records which API version a merchant has <em>pinned</em>.  When a client
 * sends a request without an explicit {@code X-API-Version} header, the version
 * stored here is used instead of {@link ApiVersion#DEFAULT}.
 *
 * <h2>Version resolution precedence</h2>
 * <ol>
 *   <li>Explicit {@code X-API-Version} request header (highest priority)</li>
 *   <li>Merchant pin stored in this entity</li>
 *   <li>{@link ApiVersion#DEFAULT} (fallback)</li>
 * </ol>
 *
 * <p>Each merchant has at most one pin row — the UNIQUE constraint on
 * {@code merchant_id} is enforced at the DB level.
 *
 * @see MerchantApiVersionService
 * @see com.firstclub.platform.version.ApiVersionedMapper
 */
@Entity
@Table(
    name = "merchant_api_versions",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_merchant_api_versions_merchant_id",
        columnNames = "merchant_id"
    ),
    indexes = @Index(name = "idx_merchant_api_versions_merchant_id", columnList = "merchant_id")
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantApiVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tenant merchant primary key. Unique — enforced by DB constraint. */
    @Column(name = "merchant_id", nullable = false, unique = true)
    private Long merchantId;

    /**
     * Pinned version string in {@code YYYY-MM-DD} format, e.g. {@code "2025-01-01"}.
     * Validated by {@link ApiVersion#fromString(String)} when written.
     */
    @Column(name = "pinned_version", nullable = false, length = 20)
    private String pinnedVersion;

    /**
     * The date from which this pin becomes effective.
     * Defaults to today when not specified.
     */
    @Column(name = "effective_from", nullable = false)
    @Builder.Default
    private LocalDate effectiveFrom = LocalDate.now();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
