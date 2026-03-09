package com.firstclub.merchant.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A merchant (tenant) account on the FirstClub platform.
 *
 * <p>Every customer, subscription, payment, and ledger entry in the V2 API is
 * scoped to exactly one merchant.  The {@code merchant_code} is an immutable
 * slug assigned at creation time and used as the stable external identifier.
 *
 * <p>Status transitions are enforced by
 * {@link com.firstclub.platform.statemachine.StateMachineValidator} via the
 * "MERCHANT" machine key.
 */
@Entity
@Table(name = "merchant_accounts", indexes = {
    @Index(name = "idx_merchant_accounts_status",  columnList = "status"),
    @Index(name = "idx_merchant_accounts_code",    columnList = "merchant_code")
})
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Immutable external slug (e.g. "ACME_CORP").  Must be unique across the platform. */
    @Column(name = "merchant_code", nullable = false, unique = true, length = 64)
    private String merchantCode;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private MerchantStatus status = MerchantStatus.PENDING;

    /** ISO 4217 currency code (e.g. "INR", "USD"). */
    @Column(name = "default_currency", nullable = false, length = 10)
    @Builder.Default
    private String defaultCurrency = "INR";

    /** ISO 3166-1 alpha-2 country code (e.g. "IN"). */
    @Column(name = "country_code", nullable = false, length = 8)
    @Builder.Default
    private String countryCode = "IN";

    /** IANA timezone identifier (e.g. "Asia/Kolkata"). */
    @Column(nullable = false, length = 64)
    @Builder.Default
    private String timezone = "Asia/Kolkata";

    @Column(name = "support_email", nullable = false)
    private String supportEmail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── Associations ──────────────────────────────────────────────────────────

    @OneToMany(mappedBy = "merchant", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude
    @Builder.Default
    private List<MerchantUser> merchantUsers = new ArrayList<>();

    @OneToOne(mappedBy = "merchant", cascade = CascadeType.ALL, fetch = FetchType.LAZY, optional = true)
    @ToString.Exclude
    private MerchantSettings settings;
}
