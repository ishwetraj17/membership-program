package com.firstclub.merchant.controller;

import com.firstclub.merchant.dto.MerchantCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.merchant.dto.MerchantStatusUpdateRequestDTO;
import com.firstclub.merchant.dto.MerchantUpdateRequestDTO;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.service.MerchantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Admin REST API for merchant/tenant management.
 *
 * All endpoints require ADMIN role.
 * Base path: /api/v2/admin/merchants
 *
 * Implemented by Shwet Raj
 */
@RestController
@RequestMapping("/api/v2/admin/merchants")
@RequiredArgsConstructor
@Validated
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Merchant Admin", description = "Admin APIs for managing merchant accounts (v2)")
public class MerchantAdminController {

    private final MerchantService merchantService;

    @PostMapping
    @Operation(summary = "Create merchant", description = "Creates a new merchant account in PENDING status")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Merchant created successfully"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "409", description = "Merchant code already taken")
    })
    public ResponseEntity<MerchantResponseDTO> createMerchant(
            @Valid @RequestBody MerchantCreateRequestDTO request) {
        log.info("Admin: creating merchant code={}", request.getMerchantCode());
        MerchantResponseDTO created = merchantService.createMerchant(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    @Operation(summary = "List all merchants", description = "Returns paginated list of all merchant accounts")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Merchants retrieved successfully")
    })
    public ResponseEntity<Page<MerchantResponseDTO>> getAllMerchants(
            @Parameter(description = "Filter by status (optional)")
            @RequestParam(required = false) MerchantStatus status,
            @Parameter(description = "Page number (0-based)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (max 100)", example = "20")
            @RequestParam(defaultValue = "20") @Max(100) int size,
            @Parameter(description = "Sort field", example = "createdAt")
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter(description = "Sort direction", example = "desc")
            @RequestParam(defaultValue = "desc") String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("asc") ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        PageRequest pageable = PageRequest.of(page, size, sort);

        Page<MerchantResponseDTO> result = (status != null)
                ? merchantService.getMerchantsByStatus(status, pageable)
                : merchantService.getAllMerchants(pageable);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get merchant by ID", description = "Retrieves a merchant account by its ID")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Merchant found"),
        @ApiResponse(responseCode = "404", description = "Merchant not found")
    })
    public ResponseEntity<MerchantResponseDTO> getMerchantById(
            @Parameter(description = "Merchant ID", example = "1")
            @Positive @PathVariable Long id) {
        return ResponseEntity.ok(merchantService.getMerchantById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update merchant", description = "Updates mutable fields of a merchant account")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Merchant updated successfully"),
        @ApiResponse(responseCode = "404", description = "Merchant not found"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public ResponseEntity<MerchantResponseDTO> updateMerchant(
            @Parameter(description = "Merchant ID", example = "1")
            @Positive @PathVariable Long id,
            @Valid @RequestBody MerchantUpdateRequestDTO request) {
        log.info("Admin: updating merchant id={}", id);
        return ResponseEntity.ok(merchantService.updateMerchant(id, request));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Update merchant status", description = "Transitions a merchant to a new status")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Status updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid status transition"),
        @ApiResponse(responseCode = "404", description = "Merchant not found")
    })
    public ResponseEntity<MerchantResponseDTO> updateMerchantStatus(
            @Parameter(description = "Merchant ID", example = "1")
            @Positive @PathVariable Long id,
            @Valid @RequestBody MerchantStatusUpdateRequestDTO request) {
        log.info("Admin: updating merchant id={} status → {}", id, request.getStatus());
        return ResponseEntity.ok(merchantService.updateMerchantStatus(id, request));
    }
}
