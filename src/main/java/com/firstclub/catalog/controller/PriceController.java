package com.firstclub.catalog.controller;

import com.firstclub.catalog.dto.PriceCreateRequestDTO;
import com.firstclub.catalog.dto.PriceResponseDTO;
import com.firstclub.catalog.dto.PriceUpdateRequestDTO;
import com.firstclub.catalog.dto.PriceVersionCreateRequestDTO;
import com.firstclub.catalog.dto.PriceVersionResponseDTO;
import com.firstclub.catalog.service.PriceService;
import com.firstclub.catalog.service.PriceVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
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

import java.util.List;

/**
 * REST API for merchant-scoped price management and price version management.
 *
 * Base path: /api/v2/merchants/{merchantId}/prices
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/prices")
@RequiredArgsConstructor
@Validated
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Price Catalog", description = "Merchant-scoped price and price version management APIs (v2)")
public class PriceController {

    private final PriceService priceService;
    private final PriceVersionService priceVersionService;

    // ── Price endpoints ────────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Create price",
               description = "Creates a new price for an existing product. priceCode must be unique within the merchant.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Price created"),
        @ApiResponse(responseCode = "400", description = "Validation error or invalid billing interval"),
        @ApiResponse(responseCode = "404", description = "Merchant or product not found"),
        @ApiResponse(responseCode = "409", description = "Duplicate priceCode within merchant")
    })
    public ResponseEntity<PriceResponseDTO> createPrice(
            @PathVariable Long merchantId,
            @Valid @RequestBody PriceCreateRequestDTO request) {
        log.info("POST /merchants/{}/prices — code={}", merchantId, request.getPriceCode());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(priceService.createPrice(merchantId, request));
    }

    @GetMapping
    @Operation(summary = "List prices",
               description = "Returns a paginated list of prices for the merchant. Filter by ?active=true/false.")
    public ResponseEntity<Page<PriceResponseDTO>> listPrices(
            @PathVariable Long merchantId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        return ResponseEntity.ok(
                priceService.listPrices(merchantId, active,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @GetMapping("/{priceId}")
    @Operation(summary = "Get price by ID")
    public ResponseEntity<PriceResponseDTO> getPrice(
            @PathVariable Long merchantId,
            @PathVariable Long priceId) {
        return ResponseEntity.ok(priceService.getPriceById(merchantId, priceId));
    }

    @PutMapping("/{priceId}")
    @Operation(summary = "Update price",
               description = "Updates mutable fields (trialDays). Amount/currency changes must use /versions.")
    public ResponseEntity<PriceResponseDTO> updatePrice(
            @PathVariable Long merchantId,
            @PathVariable Long priceId,
            @Valid @RequestBody PriceUpdateRequestDTO request) {
        return ResponseEntity.ok(priceService.updatePrice(merchantId, priceId, request));
    }

    @PostMapping("/{priceId}/deactivate")
    @Operation(summary = "Deactivate price",
               description = "Deactivates the price. Deactivated prices cannot be used for new subscriptions.")
    public ResponseEntity<PriceResponseDTO> deactivatePrice(
            @PathVariable Long merchantId,
            @PathVariable Long priceId) {
        return ResponseEntity.ok(priceService.deactivatePrice(merchantId, priceId));
    }

    // ── Price version endpoints ────────────────────────────────────────────────

    @PostMapping("/{priceId}/versions")
    @Operation(summary = "Create price version",
               description = "Schedules a new pricing version (current or future-dated). " +
                             "Previous open-ended version is automatically closed.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Price version created"),
        @ApiResponse(responseCode = "400", description = "effectiveFrom in past or invalid"),
        @ApiResponse(responseCode = "404", description = "Price not found"),
        @ApiResponse(responseCode = "409", description = "Overlapping version window")
    })
    public ResponseEntity<PriceVersionResponseDTO> createPriceVersion(
            @PathVariable Long merchantId,
            @PathVariable Long priceId,
            @Valid @RequestBody PriceVersionCreateRequestDTO request) {
        log.info("POST /merchants/{}/prices/{}/versions — effectiveFrom={}", merchantId, priceId, request.getEffectiveFrom());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(priceVersionService.createPriceVersion(merchantId, priceId, request));
    }

    @GetMapping("/{priceId}/versions")
    @Operation(summary = "List price versions",
               description = "Returns all versions for a price, ordered by effectiveFrom descending.")
    public ResponseEntity<List<PriceVersionResponseDTO>> listPriceVersions(
            @PathVariable Long merchantId,
            @PathVariable Long priceId) {
        return ResponseEntity.ok(priceVersionService.listVersions(merchantId, priceId));
    }
}
