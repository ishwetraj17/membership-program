package com.firstclub.support.controller;

import com.firstclub.support.dto.*;
import com.firstclub.support.entity.SupportNoteVisibility;
import com.firstclub.support.service.SupportCaseService;
import com.firstclub.support.service.SupportNoteService;
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
 * Ops-facing support-case API.
 *
 * <p>Exposes case lifecycle and note management at {@code /support/cases}.
 * Complements the more granular admin API at
 * {@code /api/v2/admin/support/cases} which includes listing + assignment.
 *
 * <p>All endpoints require the {@code ADMIN} role.
 *
 * <p><b>Base path:</b> {@code /support/cases}
 */
@RestController
@RequestMapping("/support/cases")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Support Cases (Ops)", description = "Ops-facing support case and note management")
public class SupportController {

    private final SupportCaseService supportCaseService;
    private final SupportNoteService supportNoteService;

    // ── Case lifecycle ────────────────────────────────────────────────────────

    @PostMapping
    @Operation(
        summary = "Open a new support case",
        description = "Creates a support case linked to a platform entity. "
                + "The linked entity must exist. A timeline event is recorded on creation."
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
        description = "Creates an immutable note on the case. Rejected when the case is CLOSED. "
                + "Use visibility=MERCHANT_VISIBLE to surface the note in the merchant portal."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Note created"),
        @ApiResponse(responseCode = "404", description = "Case not found"),
        @ApiResponse(responseCode = "409", description = "Case is already CLOSED")
    })
    public ResponseEntity<SupportNoteResponseDTO> addNote(
            @PathVariable Long id,
            @Valid @RequestBody SupportNoteCreateRequestDTO request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(supportCaseService.addNote(id, request));
    }

    @GetMapping("/{id}/notes")
    @Operation(
        summary = "List notes for a support case",
        description = "Returns all notes, oldest first. "
                + "Pass visibility=MERCHANT_VISIBLE to return only merchant-visible notes."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Notes returned"),
        @ApiResponse(responseCode = "404", description = "Case not found")
    })
    public ResponseEntity<List<SupportNoteResponseDTO>> listNotes(
            @PathVariable Long id,
            @RequestParam(required = false) SupportNoteVisibility visibility) {

        if (visibility != null) {
            return ResponseEntity.ok(supportNoteService.listVisibleNotes(id, visibility));
        }
        return ResponseEntity.ok(supportNoteService.listNotes(id));
    }
}
