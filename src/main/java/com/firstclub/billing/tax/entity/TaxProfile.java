package com.firstclub.billing.tax.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * GST registration and state profile for a merchant.
 * One merchant has at most one tax profile (unique on merchant_id).
 */
@Entity
@Table(name = "tax_profiles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class TaxProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false, unique = true)
    private Long merchantId;

    /** 15-character GSTIN of the merchant. */
    @Column(name = "gstin", nullable = false, length = 32)
    private String gstin;

    /** 2-character state code (e.g. "MH", "KA"). */
    @Column(name = "legal_state_code", nullable = false, length = 8)
    private String legalStateCode;

    @Column(name = "registered_business_name", nullable = false, length = 255)
    private String registeredBusinessName;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_mode", nullable = false, length = 16)
    private TaxMode taxMode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
