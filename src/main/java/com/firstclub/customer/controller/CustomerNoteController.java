package com.firstclub.customer.controller;

import com.firstclub.customer.dto.CustomerNoteCreateRequestDTO;
import com.firstclub.customer.dto.CustomerNoteResponseDTO;
import com.firstclub.customer.service.CustomerNoteService;
import com.firstclub.membership.service.AuditContext;
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
 * REST API for customer note management.
 *
 * Notes are immutable once created.
 * The author is resolved from the JWT-authenticated principal via {@link AuditContext}.
 *
 * Base path: /api/v2/merchants/{merchantId}/customers/{customerId}/notes
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/customers/{customerId}/notes")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Customer Notes", description = "Immutable customer note APIs (v2)")
public class CustomerNoteController {

    private final CustomerNoteService customerNoteService;
    private final AuditContext auditContext;

    @PostMapping
    @Operation(summary = "Add note to customer",
               description = "Creates an immutable note on the customer. Author is taken from the authenticated principal.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Note created"),
        @ApiResponse(responseCode = "400", description = "Validation error or author/customer not found"),
        @ApiResponse(responseCode = "404", description = "Customer not found or belongs to different merchant")
    })
    public ResponseEntity<CustomerNoteResponseDTO> addNote(
            @PathVariable Long merchantId,
            @PathVariable Long customerId,
            @Valid @RequestBody CustomerNoteCreateRequestDTO request) {

        Long authorUserId = auditContext.getCurrentUserId();
        log.info("Adding note to customer id={} by user id={}", customerId, authorUserId);

        CustomerNoteResponseDTO created =
                customerNoteService.addNote(merchantId, customerId, authorUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @Operation(summary = "List notes for customer",
               description = "Returns all notes for the customer, newest first.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "List of notes returned"),
        @ApiResponse(responseCode = "404", description = "Customer not found or belongs to different merchant")
    })
    public ResponseEntity<List<CustomerNoteResponseDTO>> listNotes(
            @PathVariable Long merchantId,
            @PathVariable Long customerId) {

        return ResponseEntity.ok(customerNoteService.listNotesForCustomer(merchantId, customerId));
    }
}
