package com.firstclub.risk.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Persisted outcome of a risk evaluation run before a payment confirmation attempt.
 */
@Entity
@Table(name = "risk_decisions", indexes = {
        @Index(name = "idx_risk_decisions_merchant_created", columnList = "merchant_id, created_at")
})
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "payment_intent_id", nullable = false)
    private Long paymentIntentId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Aggregate risk score — sum of {@code score} from each matched rule's config_json. */
    @Column(nullable = false)
    private int score;

    /** Final action derived from matched rules (strongest action wins). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RiskAction decision;

    /** JSON array summarising matched rules: [{id, ruleCode, action}, ...]. */
    @Column(name = "matched_rules_json", nullable = false, columnDefinition = "TEXT")
    private String matchedRulesJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
