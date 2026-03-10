package com.firstclub.risk.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "risk_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private RiskEventType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RiskSeverity severity;

    /** Null when the action cannot be tied to an authenticated user (e.g. IP block). */
    @Column(nullable = true)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String ip;

    @Column(length = 255)
    private String deviceId;

    /** JSON metadata — free-form contextual data. */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── Phase 18: score decay + decision audit ────────────────────────────────
    /** Raw score at the time the event was recorded (before decay). */
    @Column(name = "base_score")
    private Integer baseScore;

    /** Score after half-life time-decay relative to now. */
    @Column(name = "decayed_score")
    private Integer decayedScore;

    /** Risk action decided for this event. */
    @Column(name = "decision", length = 16)
    private String decision;

    /** JSON array of rule IDs that fired and contributed to this event. */
    @Column(name = "rule_ids_json", columnDefinition = "TEXT")
    private String ruleIdsJson;

    // ------------------------------------------------------------------
    // Nested enums kept with the entity for colocation convenience
    // ------------------------------------------------------------------

    public enum RiskEventType {
        /** A payment attempt was made (normal). */
        PAYMENT_ATTEMPT,
        /** More than 5 payment attempts in the last hour. */
        VELOCITY_EXCEEDED,
        /** The requesting IP address is on the block-list. */
        IP_BLOCKED
    }

    public enum RiskSeverity {
        LOW, MEDIUM, HIGH
    }
}
