package com.firstclub.platform.ops.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "feature_flags")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "flagKey")
public class FeatureFlag {

    @Id
    @Column(name = "flag_key", length = 128)
    private String flagKey;

    @Column(nullable = false)
    private boolean enabled;

    /**
     * "GLOBAL" — applies platform-wide unless a merchant-scoped override exists.
     * "MERCHANT" — applies only to the specific merchant_id.
     */
    @Column(nullable = false, length = 32)
    @Builder.Default
    private String scope = "GLOBAL";

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
