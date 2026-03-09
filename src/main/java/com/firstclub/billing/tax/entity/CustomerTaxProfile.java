package com.firstclub.billing.tax.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * GST registration and state profile for a customer.
 * One customer has at most one tax profile (unique on customer_id).
 */
@Entity
@Table(name = "customer_tax_profiles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CustomerTaxProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, unique = true)
    private Long customerId;

    /** Optional GSTIN — present for B2B customers, null for individuals. */
    @Column(name = "gstin", length = 32)
    private String gstin;

    /** 2-character state code (e.g. "MH", "KA"). */
    @Column(name = "state_code", nullable = false, length = 8)
    private String stateCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 16)
    private CustomerEntityType entityType;

    /** If true, no GST lines are generated for this customer. */
    @Builder.Default
    @Column(name = "tax_exempt", nullable = false)
    private boolean taxExempt = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
