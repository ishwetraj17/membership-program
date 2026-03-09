package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * General-purpose audit log for tracking security-level and lifecycle events.
 *
 * <p>Complements {@link SubscriptionHistory} (domain-level, fine-grained) with a
 * broader log of all significant actions: authentication events, admin operations,
 * and subscription lifecycle transitions propagated via Spring {@code ApplicationEvent}.
 *
 * <p>Records are immutable once written — no update operations are exposed.
 *
 * Implemented by Shwet Raj
 */
@Entity
@Table(
    name = "audit_logs",
    indexes = {
        @Index(name = "idx_audit_logs_user_id",     columnList = "user_id"),
        @Index(name = "idx_audit_logs_entity",       columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_logs_occurred_at",  columnList = "occurred_at"),
        @Index(name = "idx_audit_logs_action",       columnList = "action")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Enumerated type of the auditable action. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 60)
    private AuditAction action;

    /** Logical type of the entity affected (e.g. "Subscription", "User"). */
    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    /** Primary key of the affected entity — null for non-entity-specific actions. */
    @Column(name = "entity_id")
    private Long entityId;

    /** ID of the acting user — null for system / scheduler-triggered events. */
    @Column(name = "user_id")
    private Long userId;

    /** Human-readable summary of what happened. */
    @Column(length = 500)
    private String description;

    /** Optional JSON payload for extra context (plan IDs, reason, etc.). */
    @Column(length = 2000)
    private String metadata;

    /**
     * Correlation ID from the {@code X-Request-Id} header, injected into
     * {@link org.slf4j.MDC} by {@code RequestIdFilter}.
     * Links this audit entry to the corresponding HTTP request log lines.
     */
    @Column(name = "request_id", length = 64)
    private String requestId;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    // -----------------------------------------------------------------------
    // Audit action catalogue
    // -----------------------------------------------------------------------

    /** All auditable actions in the membership system. */
    public enum AuditAction {
        // User lifecycle
        USER_CREATED, USER_UPDATED, USER_DELETED, USER_LOGIN, USER_LOGOUT,
        // Subscription lifecycle
        SUBSCRIPTION_CREATED, SUBSCRIPTION_CANCELLED, SUBSCRIPTION_RENEWED,
        SUBSCRIPTION_UPGRADED, SUBSCRIPTION_DOWNGRADED, SUBSCRIPTION_EXPIRED,
        // Catch-all for privileged admin operations
        ADMIN_ACTION
    }
}
