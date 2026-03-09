package com.firstclub.platform.dedup;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Durable record of a business side-effect that has been applied.
 *
 * <p>The {@code (effect_type, fingerprint)} pair is UNIQUE in the DB, providing
 * a hard guarantee that the same logical effect is never persisted twice — even
 * across restarts, Redis failures, or replayed events.
 *
 * <p>The fingerprint is computed by {@link BusinessFingerprintService} as a
 * deterministic SHA-256 hash of the business-unique key fields for the effect,
 * e.g. {@code SHA-256(paymentIntentId + ":" + gatewayTxnId)} for a payment
 * capture.
 *
 * @see BusinessEffectType for valid {@code effectType} values.
 */
@Entity
@Table(
    name = "business_effect_fingerprints",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_effect_fingerprint",
        columnNames = {"effect_type", "fingerprint"}
    )
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"effectType", "fingerprint"})
public class BusinessEffectFingerprint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The kind of business effect — one of the constants in {@link BusinessEffectType}.
     */
    @Column(name = "effect_type", nullable = false, length = 64)
    private String effectType;

    /**
     * SHA-256 hex digest of the business-unique key fields for this effect.
     * Computed by {@link BusinessFingerprintService}.
     */
    @Column(nullable = false, length = 128)
    private String fingerprint;

    /**
     * The type of the underlying business entity (e.g. "PAYMENT", "REFUND_REQUEST").
     * Used to correlate which record caused the effect.
     */
    @Column(name = "reference_type", length = 64)
    private String referenceType;

    /**
     * The primary key of the underlying business entity.
     */
    @Column(name = "reference_id")
    private Long referenceId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
