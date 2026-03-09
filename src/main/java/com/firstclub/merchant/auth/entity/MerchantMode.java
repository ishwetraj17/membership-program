package com.firstclub.merchant.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "merchant_modes")
@Data
@EqualsAndHashCode(of = "merchantId")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantMode {

    /** Primary key — same as the merchant's ID (one-to-one relationship). */
    @Id
    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "sandbox_enabled", nullable = false)
    @Builder.Default
    private boolean sandboxEnabled = true;

    @Column(name = "live_enabled", nullable = false)
    @Builder.Default
    private boolean liveEnabled = false;

    /**
     * The mode used when no explicit mode is specified on a request.
     * Must be consistent with the enabled flags: SANDBOX requires sandboxEnabled=true,
     * LIVE requires liveEnabled=true.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_mode", nullable = false, length = 16)
    @Builder.Default
    private MerchantApiKeyMode defaultMode = MerchantApiKeyMode.SANDBOX;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
