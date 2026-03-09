package com.firstclub.reporting.ops.timeline.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Append-only timeline event that records what happened to a specific entity
 * (customer, subscription, invoice, payment intent, refund, or dispute).
 *
 * <p>Timeline events are <em>derived data</em> — they are projected from
 * {@link com.firstclub.events.entity.DomainEvent} domain events and must
 * never be treated as a source-of-truth replacement.
 *
 * <p><strong>Dedup / replay safety:</strong> the combination
 * {@code (source_event_id, entity_type, entity_id)} is unique (via a partial
 * DB index) when {@code source_event_id} is non-null.  Replying the event log
 * therefore skips already-written rows at the DB level.  Manually created rows
 * (e.g. from repair actions) carry {@code source_event_id = null} and are
 * never blocked by the dedup constraint.
 *
 * <p><strong>Ordering:</strong> always sort by {@code event_time DESC, id DESC}.
 */
@Entity
@Table(
    name = "ops_timeline_events",
    indexes = {
        @Index(name = "idx_timeline_entity",
               columnList = "entity_type, entity_id, event_time DESC"),
        @Index(name = "idx_timeline_correlation",
               columnList = "merchant_id, correlation_id"),
        @Index(name = "idx_timeline_event_type",
               columnList = "merchant_id, event_type, event_time DESC")
    }
)
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TimelineEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    // ── Entity reference ──────────────────────────────────────────────────────

    /** Discriminator: CUSTOMER, SUBSCRIPTION, INVOICE, PAYMENT_INTENT, REFUND, DISPUTE */
    @Column(name = "entity_type", nullable = false, length = 64)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    // ── What happened ─────────────────────────────────────────────────────────

    /** Domain event type string — matches {@link com.firstclub.events.service.DomainEventTypes}. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** Wall-clock time when the originating event occurred. */
    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    /** Short human-readable label shown in support UIs (e.g. "Subscription activated"). */
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** Optional one-sentence contextual note (e.g. "Failed on gateway Stripe, reason ISSUER_DECLINE"). */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    // ── Optional cross-link ───────────────────────────────────────────────────

    /** Type of the related entity (e.g. INVOICE when a payment event also touches an invoice). */
    @Column(name = "related_entity_type", length = 64)
    private String relatedEntityType;

    /** ID of the related entity. */
    @Column(name = "related_entity_id")
    private Long relatedEntityId;

    // ── Tracing ───────────────────────────────────────────────────────────────

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "causation_id", length = 128)
    private String causationId;

    // ── Source snapshot ───────────────────────────────────────────────────────

    /**
     * Truncated JSON from the originating domain event payload (first 500 chars).
     * Never parsed — diagnostic only.
     */
    @Column(name = "payload_preview_json", columnDefinition = "TEXT")
    private String payloadPreviewJson;

    /**
     * ID of the {@link com.firstclub.events.entity.DomainEvent} that produced
     * this row.  Null for manually created entries.  Used for dedup index.
     */
    @Column(name = "source_event_id")
    private Long sourceEventId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;
}
