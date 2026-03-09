package com.firstclub.customer.controller;

import com.firstclub.customer.dto.CustomerCreateRequestDTO;
import com.firstclub.customer.dto.CustomerResponseDTO;
import com.firstclub.customer.dto.CustomerUpdateRequestDTO;
import com.firstclub.customer.entity.CustomerStatus;
import com.firstclub.customer.service.CustomerService;
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
 * REST API for merchant-scoped customer management.
 *
 * All endpoints are scoped to a specific merchant via the {@code merchantId}
 * path variable, enforcing tenant isolation.
 *
 * Base path: /api/v2/merchants/{merchantId}/customers
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/customers")
@RequiredArgsConstructor
@Validated
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Customer", description = "Merchant-scoped customer management APIs (v2)")
public class CustomerController {

    private final CustomerService customerService;

    // ── Create ─────────────────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Create customer",
               description = "Creates a new customer under the given merchant. Email must be unique within the merchant.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Customer created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Merchant not found"),
        @ApiResponse(responseCode = "409", description = "Duplicate email or externalCustomerId within merchant")
    })
    public ResponseEntity<CustomerResponseDTO> createCustomer(
            @PathVariable Long merchantId,
            @Valid @RequestBody CustomerCreateRequestDTO request) {

        log.info("Creating customer for merchantId={}", merchantId);
        CustomerResponseDTO created = customerService.createCustomer(merchantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── List ───────────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "List customers",
               description = "Returns a paginated list of customers for the given merchant. Optionally filter by status.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Page of customers returned"),
        @ApiResponse(responseCode = "404", description = "Merchant not found")
    })
    public ResponseEntity<Page<CustomerResponseDTO>> listCustomers(
            @PathVariable Long merchantId,
            @Parameter(description = "Filter by status (ACTIVE, INACTIVE, BLOCKED)")
            @RequestParam(required = false) CustomerStatus status,
            @Positive @RequestParam(defaultValue = "0") int page,
            @Positive(message = "size must be >= 1") @Max(100) @RequestParam(defaultValue = "20") int size) {

        Page<CustomerResponseDTO> result = customerService.getCustomersByMerchant(
                merchantId, status,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return ResponseEntity.ok(result);
    }

    // ── Get by ID ──────────────────────────────────────────────────────────────

    @GetMapping("/{customerId}")
    @Operation(summary = "Get customer by ID")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Customer found"),
        @ApiResponse(responseCode = "404", description = "Customer not found or belongs to different merchant")
    })
    public ResponseEntity<CustomerResponseDTO> getCustomer(
            @PathVariable Long merchantId,
            @PathVariable Long customerId) {

        return ResponseEntity.ok(customerService.getCustomerById(merchantId, customerId));
    }

    // ── Update ─────────────────────────────────────────────────────────────────

    @PutMapping("/{customerId}")
    @Operation(summary = "Update customer", description = "Partially updates mutable fields (patch semantics).")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Customer updated"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Customer not found"),
        @ApiResponse(responseCode = "409", description = "New email or externalCustomerId already in use")
    })
    public ResponseEntity<CustomerResponseDTO> updateCustomer(
            @PathVariable Long merchantId,
            @PathVariable Long customerId,
            @Valid @RequestBody CustomerUpdateRequestDTO request) {

        log.info("Updating customer id={} for merchantId={}", customerId, merchantId);
        return ResponseEntity.ok(customerService.updateCustomer(merchantId, customerId, request));
    }

    // ── Status transitions ──────────────────────────────────────────────────────

    @PostMapping("/{customerId}/block")
    @Operation(summary = "Block customer", description = "Transitions the customer to BLOCKED status.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Customer blocked"),
        @ApiResponse(responseCode = "404", description = "Customer not found")
    })
    public ResponseEntity<CustomerResponseDTO> blockCustomer(
            @PathVariable Long merchantId,
            @PathVariable Long customerId) {

        log.info("Blocking customer id={} for merchantId={}", customerId, merchantId);
        return ResponseEntity.ok(customerService.blockCustomer(merchantId, customerId));
    }

    @PostMapping("/{customerId}/activate")
    @Operation(summary = "Activate customer", description = "Transitions the customer to ACTIVE status.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Customer activated"),
        @ApiResponse(responseCode = "404", description = "Customer not found")
    })
    public ResponseEntity<CustomerResponseDTO> activateCustomer(
            @PathVariable Long merchantId,
            @PathVariable Long customerId) {

        log.info("Activating customer id={} for merchantId={}", customerId, merchantId);
        return ResponseEntity.ok(customerService.activateCustomer(merchantId, customerId));
    }
}
