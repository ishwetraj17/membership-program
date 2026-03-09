package com.firstclub.membership.service;

import com.firstclub.membership.dto.AuditLogDTO;
import com.firstclub.membership.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service for reading and writing the general-purpose {@code audit_logs} table.
 *
 * <p>Write path: {@link #record} is called by {@code SubscriptionEventListener}
 * (inside a {@code REQUIRES_NEW} transaction after the originating transaction commits)
 * and can also be called directly for authentication events.
 *
 * <p>Read path: paginated queries exposed through {@code AdminController}.
 *
 * Implemented by Shwet Raj
 */
public interface AuditLogService {

    /**
     * Record a new audit log entry.
     *
     * @param action      the type of event
     * @param entityType  logical entity name, e.g. {@code "Subscription"} or {@code "User"}
     * @param entityId    PK of the affected entity — {@code null} for non-entity events
     * @param userId      ID of the acting user — {@code null} for scheduled/system events
     * @param description human-readable summary
     * @param metadata    optional JSON payload for extra context (plan IDs, reasons, etc.)
     */
    void record(AuditLog.AuditAction action, String entityType, Long entityId,
                Long userId, String description, String metadata);

    /** Paginated list of all entries, most recent first. */
    Page<AuditLogDTO> getAuditLogs(Pageable pageable);

    /** Paginated entries for a specific entity, most recent first. */
    Page<AuditLogDTO> getAuditLogsForEntity(String entityType, Long entityId, Pageable pageable);

    /** Paginated entries initiated by a specific user, most recent first. */
    Page<AuditLogDTO> getAuditLogsForUser(Long userId, Pageable pageable);
}
