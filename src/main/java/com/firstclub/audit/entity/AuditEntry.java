package com.firstclub.audit.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * JPA entity for the {@code audit_entries} table (originally created in V50,
 * extended in V68 with compliance-grade financial audit columns).
 *
 * <h2>Immutability contract</h2>
 * Audit entries are <em>written once and never mutated</em>.  The table has
 * no {@code updated_at} column and no service method offers an update path.
 * {@code @Column(updatable = false)} is set on every column as a JPA-layer
 * guard against accidental updates.
 *
 * <h2>Column semantics</h2>
 * <ul>
 *   <li>{@code operationType} — machine-readable constant (e.g.
 *       {@code SUBSCRIPTION_CREATE}, {@code PAYMENT_CONFIRM}) set by
 *       {@link com.firstclub.audit.aspect.FinancialAuditAspect}.</li>
 *   <li>{@code action} — human-readable free-text description.</li>
 *   <li>{@code performedBy} — actor string from request context or job name.</li>
 *   <li>{@code success} — TRUE when the operation committed; FALSE when it
 *       threw or was rolled back.</li>
 *   <li>{@code failureReason} — first 2 000 characters of the exception
 *       message when {@code success = false}.</li>
 * </ul>
 */
@Entity
@Table(
    name = "audit_entries",
    indexes = {
        @Index(name = "idx_audit_entries_merchant_time",  columnList = "merchant_id, occurred_at DESC"),
        @Index(name = "idx_audit_entries_entity",         columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_entries_request_id",     columnList = "request_id"),
        @Index(name = "idx_audit_entries_correlation_id", columnList = "correlation_id"),
        @Index(name = "idx_audit_entries_action",         columnList = "action"),
        @Index(name = "idx_audit_entries_operation_type", columnList = "operation_type, occurred_at DESC")
    }
)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
@ToString(exclude = "metadata")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false)
    private Long id;

    // ── Tracing ──────────────────────────────────────────────────────────────

    @Column(name = "request_id", length = 64, updatable = false)
    private String requestId;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    // ── Tenant / actor context ────────────────────────────────────────────────

    @Column(name = "merchant_id", updatable = false)
    private Long merchantId;

    @Column(name = "actor_id", length = 120, updatable = false)
    private String actorId;

    @Column(name = "api_version", length = 20, updatable = false)
    private String apiVersion;

    // ── Operation identity ────────────────────────────────────────────────────

    /**
     * Machine-readable constant identifying the financial mutation, e.g.
     * {@code SUBSCRIPTION_CREATE}, {@code PAYMENT_CONFIRM}.  Set by
     * {@link com.firstclub.audit.aspect.FinancialAuditAspect} from the
     * {@link com.firstclub.audit.aspect.FinancialOperation#operationType()}
     * attribute.
     */
    @Column(name = "operation_type", length = 80, updatable = false)
    private String operationType;

    /** Human-readable free-text description. */
    @Column(name = "action", length = 80, nullable = false, updatable = false)
    private String action;

    /** Actor identity string: user id, service name, or job name. */
    @Column(name = "performed_by", length = 120, updatable = false)
    private String performedBy;

    // ── What changed ─────────────────────────────────────────────────────────

    @Column(name = "entity_type", length = 80, nullable = false, updatable = false)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private Long entityId;

    // ── Outcome ───────────────────────────────────────────────────────────────

    /**
     * Whether the financial operation committed successfully.
     * Defaults to {@code true}; the aspect sets this to {@code false} when
     * the advised method throws.
     */
    @Column(name = "success", nullable = false, updatable = false)
    @Builder.Default
    private boolean success = true;

    /**
     * Trimmed exception message (max 2 000 chars) when {@code success = false}.
     * NULL when the operation succeeds.
     */
    @Column(name = "failure_reason", columnDefinition = "TEXT", updatable = false)
    private String failureReason;

    // ── Optional monetary context ─────────────────────────────────────────────

    @Column(name = "amount_minor", updatable = false)
    private Long amountMinor;

    @Column(name = "currency_code", length = 10, updatable = false)
    private String currencyCode;

    // ── Arbitrary payload ─────────────────────────────────────────────────────

    /** JSONB blob for additional context. Not part of the immutable contract. */
    @Column(name = "metadata", columnDefinition = "TEXT", updatable = false)
    private String metadata;

    // ── Network context ───────────────────────────────────────────────────────

    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    // ── Timestamp ────────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;
}
