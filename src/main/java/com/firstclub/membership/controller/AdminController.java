package com.firstclub.membership.controller;

import com.firstclub.membership.config.AppConstants;
import com.firstclub.membership.dto.AuditLogDTO;
import com.firstclub.membership.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Admin-only REST endpoints for system oversight and audit log inspection.
 *
 * <p>All routes under {@code /api/v1/admin} require {@code ROLE_ADMIN} — enforced
 * once at class level via {@code @PreAuthorize} so that individual methods cannot
 * accidentally omit the check.
 *
 * Implemented by Shwet Raj
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Operations", description = "Admin-only — audit logs and operational oversight")
public class AdminController {

    private final AuditLogService auditLogService;

    // -----------------------------------------------------------------------
    // Audit log endpoints
    // -----------------------------------------------------------------------

    @Operation(
        summary = "Get all audit logs",
        description = "Paginated view of the full system audit log, most recent entries first.")
    @ApiResponse(responseCode = "200", description = "Audit logs retrieved")
    @GetMapping("/audit-logs")
    public ResponseEntity<Page<AuditLogDTO>> getAuditLogs(
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (max 100)") @Max(100) @RequestParam(defaultValue = "" + AppConstants.DEFAULT_PAGE_SIZE) int size) {

        log.info("Admin: fetching audit logs page={}, size={}", page, size);
        return ResponseEntity.ok(auditLogService.getAuditLogs(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"))));
    }

    @Operation(
        summary = "Get audit logs for a specific entity",
        description = "All audit events for one entity — e.g. entity_type='Subscription', entity_id=42.")
    @ApiResponse(responseCode = "200", description = "Audit logs retrieved")
    @GetMapping("/audit-logs/entity/{entityType}/{entityId}")
    public ResponseEntity<Page<AuditLogDTO>> getAuditLogsForEntity(
            @Parameter(description = "Entity type, e.g. 'Subscription' or 'User'")
                @PathVariable String entityType,
            @Parameter(description = "Entity primary key")
                @Positive @PathVariable Long entityId,
            @RequestParam(defaultValue = "0") int page,
            @Max(100) @RequestParam(defaultValue = "" + AppConstants.DEFAULT_PAGE_SIZE) int size) {

        log.info("Admin: fetching audit logs for {}/{}", entityType, entityId);
        return ResponseEntity.ok(auditLogService.getAuditLogsForEntity(
                entityType, entityId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"))));
    }

    @Operation(
        summary = "Get audit logs for a specific user",
        description = "All audit events initiated by a user — useful for incident investigation.")
    @ApiResponse(responseCode = "200", description = "Audit logs retrieved")
    @GetMapping("/audit-logs/user/{userId}")
    public ResponseEntity<Page<AuditLogDTO>> getAuditLogsForUser(
            @Parameter(description = "User ID") @Positive @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @Max(100) @RequestParam(defaultValue = "" + AppConstants.DEFAULT_PAGE_SIZE) int size) {

        log.info("Admin: fetching audit logs for user {}", userId);
        return ResponseEntity.ok(auditLogService.getAuditLogsForUser(
                userId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"))));
    }
}
