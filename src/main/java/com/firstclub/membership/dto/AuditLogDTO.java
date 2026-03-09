package com.firstclub.membership.dto;

import com.firstclub.membership.entity.AuditLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Read-only projection of an {@link AuditLog} entry returned by admin API endpoints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogDTO {
    private Long id;
    private AuditLog.AuditAction action;
    private String entityType;
    private Long entityId;
    private Long userId;
    private String description;
    private String metadata;
    /** X-Request-Id correlation token — links the entry to the originating HTTP request. */
    private String requestId;
    private LocalDateTime occurredAt;
}
