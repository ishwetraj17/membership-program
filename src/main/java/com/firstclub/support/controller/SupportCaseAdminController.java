package com.firstclub.support.controller;

import com.firstclub.support.dto.*;
import com.firstclub.support.entity.SupportCaseStatus;
import com.firstclub.support.service.SupportCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin REST API for internal support / ops case management.
 *
 * <p>All endpoints require the {@code ADMIN} role.  Cases are linked to any
 * platform entity (customer, subscription, invoice, payment, refund,
 * dispute, or recon mismatch) and appear on the entity's timeline.
 *
 * <p>Base path: {@code /api/v2/admin/support/cases}
 */
@RestController
@RequestMapping("/api/v2/admin/support/cases")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Support Cases", description = "Internal ops support-case management (v2)")
public class SupportCaseAdminController {

    private final SupportCaseService supportCaseService;

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping
    @Operation(
        summary = "Open a new support case",
        description = "Creates a support case linked to a platform entity. "
                + "The linked entity must exist. A timeline event is written on creation."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Support case created"),
        @ApiResponse(responseCode = "400", description = "Validation error or unknown entity type"),
        @ApiResponse(responseCode = "422", description = "Linked entity not found")
    })
    public ResponseEntity<SupportCaseResponseDTO> createCase(
            @Valid @RequestBody SupportCaseCreateRequestDTO request) {

        log.info("POST /support/cases merchant={} entity={}/{}",
                request.getMerchantId(), request.getLinkedEntityType(), request.getLinkedEntityId());
        return ResponseEntity.status(HttpStatus.CREATED).body(supportCaseService.createCase(request));
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(
        summary = "List support cases",
        description = "Returns cases for a merchant. Optionally filter by status, "
                + "linkedEntityType + linkedEntityId (must be supplied together)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cases returned")
    })
    public ResponseEntity<List<SupportCaseResponseDTO>> listCases(
            @RequestParam Long merchantId,
            @RequestParam(required = false) SupportCaseStatus status,
            @RequestParam(required = false) String linkedEntityType,
            @RequestParam(required = false) Long linkedEntityId) {

        return ResponseEntity.ok(
                supportCaseService.listCases(merchantId, status, linkedEntityType, linkedEntityId));
    }

    // ── Get ───────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @Operation(summary = "Get support case by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Case found"),
        @ApiResponse(responseCode = "404", description = "Case not found")
    })
    public ResponseEntity<SupportCaseResponseDTO> getCase(@PathVariable Long id) {
        return ResponseEntity.ok(supportCaseService.getCase(id));
    }

    // ── Notes ─────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/notes")
    @Operation(
        summary = "Add a note to a support case",
        description = "Creates an immutable note on the case. Rejected when the case is CLOSED."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Note created"),
        @ApiResponse(responseCode = "404", description = "Case not found"),
        @ApiResponse(responseCode = "409", description = "Case is already CLOSED")
    })
    public ResponseEntity<SupportNoteResponseDTO> addNote(
            @PathVariable Long id,
            @Valid @RequestBody SupportNoteCreateRequestDTO request) {

        return ResponseEntity.status(HttpStatus.CREATED).body(supportCaseService.addNote(id, request));
    }

    @GetMapping("/{id}/notes")
    @Operation(summary = "List notes for a support case", description = "Returns all notes, oldest first.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Notes returned"),
        @ApiResponse(responseCode = "404", description = "Case not found")
    })
    public ResponseEntity<List<SupportNoteResponseDTO>> listNotes(@PathVariable Long id) {
        return ResponseEntity.ok(supportCaseService.listNotes(id));
    }

    // ── Assign ────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/assign")
    @Operation(
        summary = "Assign a support case to an operator",
        description = "Sets (or updates) the ownerUserId and transitions OPEN → IN_PROGRESS. "
                + "Rejected when the case is CLOSED."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Case assigned"),
        @ApiResponse(responseCode = "404", description = "Case not found"),
        @ApiResponse(responseCode = "409", description = "Case is already CLOSED")
    })
    public ResponseEntity<SupportCaseResponseDTO> assignCase(
            @PathVariable Long id,
            @Valid @RequestBody SupportCaseAssignRequestDTO request) {

        return ResponseEntity.ok(supportCaseService.assignCase(id, request));
    }

    // ── Close ─────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/close")
    @Operation(
        summary = "Close a support case",
        description = "Transitions the case to CLOSED and writes a timeline event. "
                + "Returns 409 if already CLOSED."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Case closed"),
        @ApiResponse(responseCode = "404", description = "Case not found"),
        @ApiResponse(responseCode = "409", description = "Case is already CLOSED")
    })
    public ResponseEntity<SupportCaseResponseDTO> closeCase(@PathVariable Long id) {
        return ResponseEntity.ok(supportCaseService.closeCase(id));
    }
}
