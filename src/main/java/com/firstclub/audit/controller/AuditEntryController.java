package com.firstclub.audit.controller;

import com.firstclub.audit.dto.AuditEntryDTO;
import com.firstclub.audit.service.AuditEntryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for the compliance audit trail.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /audit/entries?entityType=&entityId=} — entries for one entity</li>
 *   <li>{@code GET /audit/entries/merchant/{merchantId}} — entries for a merchant</li>
 *   <li>{@code GET /audit/entries/failures} — all failed operations</li>
 * </ul>
 *
 * <p>All endpoints are admin-only to prevent data leakage of financial audit logs.
 */
@RestController
@RequestMapping("/audit/entries")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Audit Trail", description = "Compliance-grade financial audit log queries")
public class AuditEntryController {

    private final AuditEntryService auditEntryService;

    /**
     * Query audit entries for a specific domain entity.
     *
     * @param entityType domain entity class name, e.g. {@code "Subscription"}
     * @param entityId   primary key of the entity
     * @param pageable   pagination parameters (default size=20, sorted by occurredAt desc)
     */
    @GetMapping
    @Operation(
        summary     = "List audit entries for an entity",
        description = "Returns a paginated audit trail for the specified entity type + id."
    )
    public ResponseEntity<Page<AuditEntryDTO>> findByEntity(
            @RequestParam String entityType,
            @RequestParam Long   entityId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(auditEntryService.findByEntity(entityType, entityId, pageable));
    }

    /**
     * Query all audit entries for a merchant, newest first.
     */
    @GetMapping("/merchant/{merchantId}")
    @Operation(summary = "List audit entries for a merchant")
    public ResponseEntity<Page<AuditEntryDTO>> findByMerchant(
            @PathVariable Long merchantId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(auditEntryService.findByMerchant(merchantId, pageable));
    }

    /**
     * Query all failed financial operations (compliance / alert feed).
     */
    @GetMapping("/failures")
    @Operation(
        summary     = "List all failed financial operations",
        description = "Returns all audit entries where success=false, newest first."
    )
    public ResponseEntity<Page<AuditEntryDTO>> findFailures(
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(auditEntryService.findFailures(pageable));
    }
}
