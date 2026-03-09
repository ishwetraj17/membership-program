package com.firstclub.risk.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A configurable rule in the risk evaluation engine.
 *
 * <p>Rules with {@code merchantId = null} are platform-wide and apply to every merchant.
 * Merchant-specific rules are evaluated alongside platform rules, sorted by ascending priority.
 */
@Entity
@Table(name = "risk_rules", indexes = {
        @Index(name = "idx_risk_rules_lookup", columnList = "merchant_id, active, priority")
})
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** null = platform-wide rule; non-null = applies to a specific merchant only. */
    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "rule_code", nullable = false, length = 64)
    private String ruleCode;

    /** Must match a registered {@link com.firstclub.risk.service.RuleEvaluator#ruleType()}. */
    @Column(name = "rule_type", nullable = false, length = 64)
    private String ruleType;

    /** JSON blob consumed by the matching evaluator (e.g. {@code {"threshold":5,"score":30}}). */
    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    private String configJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RiskAction action;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /** Lower number = evaluated first. */
    @Column(nullable = false)
    private int priority;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
