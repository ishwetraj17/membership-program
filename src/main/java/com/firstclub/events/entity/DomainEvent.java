package com.firstclub.events.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Immutable domain event record.
 * This table is append-only — no UPDATE or DELETE operations are performed.
 *
 * <p>V29 adds versioning and routing metadata:
 * {@code eventVersion}, {@code schemaVersion}, {@code correlationId},
 * {@code causationId}, {@code aggregateType}, {@code aggregateId}, {@code merchantId}.
 */
@Entity
@Table(name = "domain_events", indexes = {
        @Index(name = "idx_domain_events_created_at",       columnList = "created_at"),
        @Index(name = "idx_domain_events_type",             columnList = "event_type"),
        @Index(name = "idx_domain_events_merchant_created", columnList = "merchant_id, created_at"),
        @Index(name = "idx_domain_events_aggregate",        columnList = "aggregate_type, aggregate_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class DomainEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** Monotonically increasing version for this event type (default 1). */
    @Builder.Default
    @Column(name = "event_version", nullable = false)
    private int eventVersion = 1;

    /** Version of the JSON schema used for the payload (default 1). */
    @Builder.Default
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion = 1;

    /** Trace/request correlation identifier propagated from the originating request. */
    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    /** ID of the event that caused this one (for event chains). */
    @Column(name = "causation_id", length = 128)
    private String causationId;

    /** DDD aggregate type, e.g. "Subscription", "Invoice", "Payment". */
    @Column(name = "aggregate_type", length = 64)
    private String aggregateType;

    /** Aggregate primary-key as a string for cross-type comparison. */
    @Column(name = "aggregate_id", length = 128)
    private String aggregateId;

    /** Owning merchant, for tenant-scoped replay and event queries. */
    @Column(name = "merchant_id")
    private Long merchantId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── V59 Phase 13: replay tracking ─────────────────────────────────────────

    /**
     * {@code true} when this event row is itself a replay of another event.
     * The original event is never modified — replay records are appended as new rows.
     */
    @Builder.Default
    @Column(name = "replayed", nullable = false)
    private boolean replayed = false;

    /** Timestamp when this replay event was created; {@code null} on original events. */
    @Column(name = "replayed_at")
    private LocalDateTime replayedAt;

    /**
     * Primary key of the original event this row replays.
     * {@code null} on original (non-replay) events.
     */
    @Column(name = "original_event_id")
    private Long originalEventId;
}
