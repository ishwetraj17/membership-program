package com.firstclub.outbox.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A domain event written atomically into the outbox table alongside the
 * business change that produced it.
 *
 * <p>Status lifecycle:
 * <pre>
 *  NEW  ──► PROCESSING ──► DONE
 *                      └──► NEW  (retry, attempts++)
 *                      └──► FAILED  (attempts >= MAX, DLQ written)
 * </pre>
 */
@Entity
@Table(name = "outbox_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxEventStatus status;

    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    // ── V29 metadata ──────────────────────────────────────────────────────────

    @Builder.Default
    @Column(name = "event_version", nullable = false)
    private int eventVersion = 1;

    @Builder.Default
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion = 1;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "causation_id", length = 128)
    private String causationId;

    @Column(name = "aggregate_type", length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", length = 128)
    private String aggregateId;

    @Column(name = "merchant_id")
    private Long merchantId;

    // ── V47 processing lease + failure categorisation ─────────────────────

    /**
     * Timestamp when this event entered {@link OutboxEventStatus#PROCESSING}.
     * Reset to {@code null} when the event returns to NEW on retry or on
     * stale-lease recovery.
     */
    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    /**
     * Hostname and PID of the JVM that claimed the processing lease.
     * Format: {@code hostname:pid}, e.g. {@code worker-1:42}.
     * Reset to {@code null} alongside {@link #processingStartedAt} on recovery.
     */
    @Column(name = "processing_owner", length = 128)
    private String processingOwner;

    /**
     * Optional idempotency fingerprint set by the event handler.
     * When set, the handler can use this to guard against duplicate
     * application of the same business effect.
     */
    @Column(name = "handler_fingerprint", length = 255)
    private String handlerFingerprint;

    /**
     * Coarse failure category of the most recent processing failure.
     * Copied to {@code dead_letter_messages.failure_category} on DLQ write.
     * One of: TRANSIENT_ERROR, PAYLOAD_PARSE_ERROR, ACCOUNTING_ERROR,
     * BUSINESS_RULE_VIOLATION, DEDUP_DUPLICATE, HANDLER_NOT_FOUND, UNKNOWN.
     */
    @Column(name = "failure_category", length = 64)
    private String failureCategory;

    // ── V58 Phase 12: per-aggregate ordering sequence + heartbeat lease ───────

    /** Monotonically increasing sequence per (aggregateType, aggregateId) pair. */
    @Column(name = "aggregate_sequence")
    private Long aggregateSequence;

    /**
     * Timestamp after which this processing lease is considered expired and can
     * be reclaimed by {@link com.firstclub.outbox.lease.OutboxLeaseRecoveryService}.
     * Renewed every ~60 s by {@link com.firstclub.outbox.lease.OutboxLeaseHeartbeat}.
     */
    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    // -------------------------------------------------------------------------
    // Status enum
    // -------------------------------------------------------------------------

    public enum OutboxEventStatus {
        NEW,
        PROCESSING,
        DONE,
        FAILED
    }
}
