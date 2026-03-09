package com.firstclub.merchant.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "merchant_api_keys",
    indexes = {
        @Index(name = "idx_merchant_api_keys_merchant_status", columnList = "merchant_id, status"),
        @Index(name = "idx_merchant_api_keys_prefix",          columnList = "key_prefix")
    }
)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** Stored in plaintext — used as a fast lookup key for authentication. */
    @Column(name = "key_prefix", nullable = false, length = 32)
    private String keyPrefix;

    /** SHA-256 digest of the full raw key.  The raw key is NEVER stored. */
    @Column(name = "key_hash", nullable = false, length = 255)
    private String keyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MerchantApiKeyMode mode;

    /** JSON array of scope strings, e.g. ["customers:read","payments:write"]. */
    @Column(name = "scopes_json", nullable = false, columnDefinition = "TEXT")
    private String scopesJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private MerchantApiKeyStatus status = MerchantApiKeyStatus.ACTIVE;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
