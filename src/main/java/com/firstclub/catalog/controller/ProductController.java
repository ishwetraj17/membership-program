package com.firstclub.catalog.controller;

import com.firstclub.catalog.dto.ProductCreateRequestDTO;
import com.firstclub.catalog.dto.ProductResponseDTO;
import com.firstclub.catalog.dto.ProductUpdateRequestDTO;
import com.firstclub.catalog.entity.ProductStatus;
import com.firstclub.catalog.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
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
 * REST API for merchant-scoped product catalog management.
 *
 * Base path: /api/v2/merchants/{merchantId}/products
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/products")
@RequiredArgsConstructor
@Validated
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Product Catalog", description = "Merchant-scoped product management APIs (v2)")
public class ProductController {

    private final ProductService productService;

    @PostMapping
    @Operation(summary = "Create product",
               description = "Creates a new product in the merchant's catalog. productCode must be unique within the merchant.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Product created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Merchant not found"),
        @ApiResponse(responseCode = "409", description = "Duplicate productCode within merchant")
    })
    public ResponseEntity<ProductResponseDTO> createProduct(
            @PathVariable Long merchantId,
            @Valid @RequestBody ProductCreateRequestDTO request) {
        log.info("POST /merchants/{}/products — code={}", merchantId, request.getProductCode());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.createProduct(merchantId, request));
    }

    @GetMapping
    @Operation(summary = "List products",
               description = "Returns a paginated list of products for the merchant with optional status filter.")
    public ResponseEntity<Page<ProductResponseDTO>> listProducts(
            @PathVariable Long merchantId,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(defaultValue = "0") @Positive(message = "page must be positive") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        return ResponseEntity.ok(
                productService.listProducts(merchantId, status,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Get product by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Product found"),
        @ApiResponse(responseCode = "404", description = "Product not found or does not belong to merchant")
    })
    public ResponseEntity<ProductResponseDTO> getProduct(
            @PathVariable Long merchantId,
            @PathVariable Long productId) {
        return ResponseEntity.ok(productService.getProductById(merchantId, productId));
    }

    @PutMapping("/{productId}")
    @Operation(summary = "Update product",
               description = "Updates mutable fields (name, description). productCode is immutable.")
    public ResponseEntity<ProductResponseDTO> updateProduct(
            @PathVariable Long merchantId,
            @PathVariable Long productId,
            @Valid @RequestBody ProductUpdateRequestDTO request) {
        return ResponseEntity.ok(productService.updateProduct(merchantId, productId, request));
    }

    @PostMapping("/{productId}/archive")
    @Operation(summary = "Archive product",
               description = "Marks the product as ARCHIVED. Archived products cannot be used for new subscriptions.")
    public ResponseEntity<ProductResponseDTO> archiveProduct(
            @PathVariable Long merchantId,
            @PathVariable Long productId) {
        return ResponseEntity.ok(productService.archiveProduct(merchantId, productId));
    }
}
